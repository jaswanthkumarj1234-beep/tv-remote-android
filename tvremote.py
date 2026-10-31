"""
TV Remote for Android (runs inside Termux, on the phone itself - no laptop needed).

The phone talks straight to the Samsung TV over home Wi-Fi.
Open  http://localhost:8767  in Chrome on the same phone, then "Add to Home screen".

Safety: listens on the phone only (127.0.0.1), and rejects web pages from other sites,
so nobody on the Wi-Fi and no random website can use it.
"""
import asyncio
import base64
import json
import os
import re
import socket
import time
from urllib.parse import urlparse

from aiohttp import ClientSession, WSMsgType, web

PORT = int(os.environ.get("TVREMOTE_PORT", "8767"))
HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_FILE = os.path.join(HERE, "tv_config.json")
TV_TOKEN_FILE = os.path.join(HERE, "tv_token.txt")
DEFAULT_CONFIG = {"ip": "", "mac": "", "remote_name": "AndroidPad"}
KEY_RE = re.compile(r"^KEY_[A-Z0-9_]{1,24}$")
APP_RE = re.compile(r"^[A-Za-z0-9._-]{1,40}$")
IP_RE = re.compile(r"^\d{1,3}(\.\d{1,3}){3}$")
MAC_RE = re.compile(r"^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")


def load_config():
    cfg = dict(DEFAULT_CONFIG)
    try:
        cfg.update(json.load(open(CONFIG_FILE, encoding="utf-8")))
    except Exception:
        pass
    return cfg


def save_config(cfg):
    with open(CONFIG_FILE, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)


def wake_on_lan(mac, ip):
    raw = bytes.fromhex(mac.replace(":", "").replace("-", ""))
    pkt = b"\xff" * 6 + raw * 16
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    for addr in ("255.255.255.255", ip.rsplit(".", 1)[0] + ".255"):
        for port in (9, 7):
            try:
                s.sendto(pkt, (addr, port))
            except Exception:
                pass
    s.close()


def local_only(request):
    """Only this phone, and only pages served by this app (blocks other websites)."""
    if request.remote not in ("127.0.0.1", "::1"):
        return False
    origin = request.headers.get("Origin")
    if origin:
        host = urlparse(origin).netloc
        if host not in (f"localhost:{PORT}", f"127.0.0.1:{PORT}"):
            return False
    return True


class TV:
    GRID = ["ABCDEFG", "HIJKLMN", "OPQRSTU", "VWXYZ_'"]
    KEY_DELAY = 0.13

    def __init__(self):
        self.cfg = load_config()
        self.session = None
        self.ws = None
        self.reader = None
        self.state = "offline"
        self.msg = "TV not reachable"
        self.clients = set()
        self.clock = asyncio.Lock()
        self.gridlock = asyncio.Lock()
        self.ime = False
        self.pos = (0, 0)

    def _state_msg(self):
        return {"t": "state", "s": self.state, "msg": self.msg}

    async def broadcast(self, obj):
        for c in list(self.clients):
            try:
                await c.send_json(obj)
            except Exception:
                self.clients.discard(c)

    async def set_state(self, state, msg=""):
        self.state, self.msg = state, msg
        if state != "connected":
            self.ime = False
        await self.broadcast(self._state_msg())

    def read_tv_token(self):
        try:
            return open(TV_TOKEN_FILE, encoding="utf-8").read().strip()
        except Exception:
            return ""

    def clear_tv_token(self):
        try:
            os.remove(TV_TOKEN_FILE)
        except Exception:
            pass

    def _connected(self):
        return self.ws is not None and not self.ws.closed

    async def drop(self):
        ws, self.ws = self.ws, None
        if self.reader and self.reader is not asyncio.current_task():
            self.reader.cancel()
        self.reader = None
        if ws is not None and not ws.closed:
            try:
                await ws.close()
            except Exception:
                pass
        if self.state != "offline":
            await self.set_state("offline", "TV not reachable")

    async def _read_loop(self, ws):
        try:
            async for m in ws:
                if m.type == WSMsgType.TEXT:
                    try:
                        await self._on_tv_message(m.data)
                    except Exception:
                        pass
                elif m.type in (WSMsgType.ERROR, WSMsgType.CLOSE, WSMsgType.CLOSED):
                    break
        except Exception:
            pass
        if self.ws is ws:
            self.ws = None
            await self.set_state("offline", "TV not reachable")

    async def _on_tv_message(self, raw):
        d = json.loads(raw)
        ev = d.get("event", "")
        if ev == "ms.remote.imeStart":
            self.ime = True
            await self.broadcast({"t": "ime", "on": True, "text": self._b64(d.get("data"))})
        elif ev == "ms.remote.imeUpdate" and not self.ime:
            self.ime = True
            await self.broadcast({"t": "ime", "on": True, "text": self._b64(d.get("data"))})
        elif ev in ("ms.remote.imeEnd", "ms.remote.imeDone"):
            self.ime = False
            await self.broadcast({"t": "ime", "on": False})

    @staticmethod
    def _b64(data):
        if isinstance(data, str) and data:
            try:
                return base64.b64decode(data).decode("utf-8", "replace")
            except Exception:
                return ""
        return ""

    async def connect(self):
        async with self.clock:
            if self._connected():
                return True
            if self.session is None or self.session.closed:
                self.session = ClientSession()
            ip = self.cfg["ip"]
            if not ip:
                await self.set_state("offline", "First time: open TV settings below and enter your TV IP address")
                return False
            name = base64.b64encode(self.cfg["remote_name"].encode()).decode()
            for attempt in (1, 2):
                tok = self.read_tv_token()
                url = f"wss://{ip}:8002/api/v2/channels/samsung.remote.control?name={name}"
                if tok:
                    url += f"&token={tok}"
                try:
                    ws = await asyncio.wait_for(self.session.ws_connect(url, ssl=False, heartbeat=None), 8)
                except Exception:
                    await self.set_state("offline", "TV not reachable - is it on, and on the same Wi-Fi?")
                    return False
                await self.set_state("waiting", "Press ALLOW on the TV using its own remote")
                deadline = asyncio.get_event_loop().time() + (12 if tok else 70)
                ok, refused = False, False
                try:
                    while True:
                        left = deadline - asyncio.get_event_loop().time()
                        if left <= 0:
                            break
                        m = await asyncio.wait_for(ws.receive(), left)
                        if m.type != WSMsgType.TEXT:
                            break
                        try:
                            d = json.loads(m.data)
                        except ValueError:
                            continue
                        ev = d.get("event", "")
                        if ev == "ms.channel.connect":
                            new = (d.get("data") or {}).get("token")
                            if new:
                                with open(TV_TOKEN_FILE, "w", encoding="utf-8") as f:
                                    f.write(str(new))
                            ok = True
                            break
                        if ev in ("ms.channel.unauthorized", "ms.channel.timeOut"):
                            refused = True
                            break
                except Exception:
                    pass
                if ok:
                    self.ws = ws
                    self.reader = asyncio.ensure_future(self._read_loop(ws))
                    await self.set_state("connected", "TV connected")
                    return True
                try:
                    await ws.close()
                except Exception:
                    pass
                if tok and attempt == 1:
                    self.clear_tv_token()
                    continue
                await self.set_state("offline", "TV refused - Allow was not pressed" if refused or not tok
                                     else "TV not reachable")
                return False
            return False

    async def send(self, payload):
        for _ in range(2):
            if not await self.connect():
                return False
            try:
                await self.ws.send_json(payload)
                return True
            except Exception:
                await self.drop()
        return False

    async def key(self, k):
        if KEY_RE.match(k):
            await self.send({"method": "ms.remote.control", "params": {
                "Cmd": "Click", "DataOfCmd": k, "Option": "false", "TypeOfRemote": "SendRemoteKey"}})

    async def text(self, s):
        await self.send({"method": "ms.remote.control", "params": {
            "Cmd": base64.b64encode(s[:200].encode("utf-8")).decode(), "DataOfCmd": "base64",
            "Option": "false", "TypeOfRemote": "SendInputString"}})

    async def imeend(self):
        await self.send({"method": "ms.remote.control", "params": {
            "Cmd": "", "DataOfCmd": "base64", "Option": "false", "TypeOfRemote": "SendInputEnd"}})

    async def app(self, app_id):
        if not APP_RE.match(app_id):
            return
        await self.send({"method": "ms.channel.emit", "params": {
            "event": "ed.apps.launch", "to": "host",
            "data": {"action_type": "DEEP_LINK", "appId": app_id}}})

    async def power_off(self):
        if self._connected():
            await self.key("KEY_POWER")

    async def power_on(self):
        if not self.cfg["mac"] or not self.cfg["ip"]:
            await self.set_state("offline", "Power on needs the TV IP and MAC in TV settings")
            return
        wake_on_lan(self.cfg["mac"], self.cfg["ip"])
        await self.set_state("offline", "Wake signal sent - give the TV about 10 seconds")
        for _ in range(8):
            await asyncio.sleep(3)
            if await self.connect():
                return

    # ---- YouTube grid keyboard ----
    def _grid_path(self, target):
        r, c = self.pos
        keys = []
        if target == "SPACE":
            tr, tc = 3, 0
        elif target == "BACK":
            tr, tc = 0, 7
        else:
            tr = tc = None
            for i, row in enumerate(self.GRID):
                j = row.find(target)
                if j >= 0:
                    tr, tc = i, j
            if tr is None:
                return None
        if r == 4:
            keys.append("KEY_UP")
            r, c = 3, 0
        if tc == 7:
            while r > tr:
                keys.append("KEY_UP")
                r -= 1
            while r < tr:
                keys.append("KEY_DOWN")
                r += 1
            while c < 7:
                keys.append("KEY_RIGHT")
                c += 1
        else:
            if c == 7:
                keys.append("KEY_LEFT")
                c = 6
            while c < tc:
                keys.append("KEY_RIGHT")
                c += 1
            while c > tc:
                keys.append("KEY_LEFT")
                c -= 1
            while r < tr:
                keys.append("KEY_DOWN")
                r += 1
            while r > tr:
                keys.append("KEY_UP")
                r -= 1
        if target == "SPACE":
            keys.append("KEY_DOWN")
            r, c = 4, 0
        self.pos = (r, c)
        return keys

    async def _press(self, keys):
        for k in keys:
            await self.key(k)
            await asyncio.sleep(self.KEY_DELAY)

    async def grid_type(self, s):
        async with self.gridlock:
            for ch in s[:60]:
                target = "SPACE" if ch == " " else ch.upper()
                if target != "SPACE" and not any(target in row for row in self.GRID):
                    continue
                keys = self._grid_path(target)
                if keys is not None:
                    await self._press(keys + ["KEY_ENTER"])

    async def grid_backspace(self, n):
        async with self.gridlock:
            for _ in range(max(0, min(int(n), 40))):
                await self._press(self._grid_path("BACK") + ["KEY_ENTER"])

    async def supervisor(self):
        while True:
            try:
                if not self._connected():
                    await self.connect()
            except Exception:
                pass
            await asyncio.sleep(15)

    async def close(self):
        await self.drop()
        if self.session and not self.session.closed:
            await self.session.close()


async def index(request):
    if not local_only(request):
        return web.Response(status=403, text="Forbidden")
    return web.Response(text=PAGE, content_type="text/html", headers={"Cache-Control": "no-store"})


async def manifest(request):
    data = {"name": "TV Remote", "short_name": "TV Remote", "start_url": "/", "display": "standalone",
            "background_color": "#F5F4EE", "theme_color": "#F5F4EE",
            "icons": [{"src": "/icon.svg", "sizes": "any", "type": "image/svg+xml", "purpose": "any"}]}
    return web.json_response(data)


async def icon(request):
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect width="100" height="100" rx="22" fill="#D97757"/>'
           '<g stroke="#fff" stroke-width="7" stroke-linecap="round"><path d="M50 22v56M26 36l48 28M74 36L26 64"/></g></svg>')
    return web.Response(text=svg, content_type="image/svg+xml")


async def config_api(request):
    if not local_only(request):
        return web.Response(status=403, text="Forbidden")
    tv = request.app["tv"]
    if request.method == "GET":
        return web.json_response({"ip": tv.cfg["ip"], "mac": tv.cfg["mac"]})
    try:
        d = await request.json()
        ip, mac = str(d.get("ip", "")).strip(), str(d.get("mac", "")).strip()
    except Exception:
        return web.Response(status=400, text="bad request")
    if not IP_RE.match(ip) or (mac and not MAC_RE.match(mac)):
        return web.json_response({"ok": False, "error": "Check the IP and MAC format"}, status=400)
    tv.cfg["ip"] = ip
    if mac:
        tv.cfg["mac"] = mac.upper().replace("-", ":")
    save_config(tv.cfg)
    tv.clear_tv_token()
    await tv.drop()
    asyncio.ensure_future(tv.connect())
    return web.json_response({"ok": True})


async def ws_handler(request):
    if not local_only(request):
        return web.Response(status=403, text="Forbidden")
    tv = request.app["tv"]
    ws = web.WebSocketResponse(heartbeat=10, max_msg_size=4096)
    await ws.prepare(request)
    tv.clients.add(ws)
    try:
        await ws.send_json(tv._state_msg())
        await ws.send_json({"t": "ime", "on": tv.ime})
        if not tv._connected():
            asyncio.ensure_future(tv.connect())
        async for m in ws:
            if m.type == WSMsgType.TEXT:
                try:
                    d = json.loads(m.data)
                    t = d.get("t")
                    if t == "key":
                        asyncio.ensure_future(tv.key(str(d.get("k", ""))))
                    elif t == "text":
                        asyncio.ensure_future(tv.text(str(d.get("s", ""))))
                    elif t == "app":
                        asyncio.ensure_future(tv.app(str(d.get("id", ""))))
                    elif t == "grid":
                        if "bs" in d:
                            asyncio.ensure_future(tv.grid_backspace(d.get("bs", 0)))
                        else:
                            asyncio.ensure_future(tv.grid_type(str(d.get("s", ""))))
                    elif t == "gridreset":
                        tv.pos = (0, 0)
                    elif t == "imeend":
                        asyncio.ensure_future(tv.imeend())
                    elif t == "poweron":
                        asyncio.ensure_future(tv.power_on())
                    elif t == "poweroff":
                        asyncio.ensure_future(tv.power_off())
                    elif t == "retry":
                        asyncio.ensure_future(tv.connect())
                except (ValueError, TypeError, AttributeError):
                    pass
            elif m.type in (WSMsgType.ERROR, WSMsgType.CLOSE):
                break
    finally:
        tv.clients.discard(ws)
    return ws


async def on_startup(app):
    app["tv"] = TV()
    app["sup"] = asyncio.ensure_future(app["tv"].supervisor())


async def on_cleanup(app):
    app["sup"].cancel()
    await app["tv"].close()


PAGE = r"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<meta name="theme-color" content="#F5F4EE"><link rel="manifest" href="/manifest.json"><link rel="icon" href="/icon.svg">
<title>TV Remote</title>
<style>
:root{--bg:#F5F4EE;--card:#FFFFFF;--line:#E6E3D8;--fg:#1F1E1D;--mut:#7B766B;--acc:#D97757;--accd:#C6613F;--accbg:#FBEDE6;--ok:#4F8A5B;--bad:#C0453A;--warn:#C98A1B}
@media(prefers-color-scheme:dark){:root{--bg:#262624;--card:#30302E;--line:#41403D;--fg:#F0EEE6;--mut:#A39E92;--acc:#E08A68;--accd:#D97757;--accbg:#3B2E28;--ok:#7DB88A;--bad:#E8756A;--warn:#E0B04A}}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent;-webkit-user-select:none;user-select:none;touch-action:manipulation}
html,body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.4 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;overscroll-behavior:none}
#app{max-width:480px;margin:0 auto;padding:max(14px,env(safe-area-inset-top)) 14px max(20px,env(safe-area-inset-bottom))}
header{display:flex;align-items:center;gap:10px;padding:6px 2px 14px}
.logo{color:var(--acc);font-size:26px;line-height:1}
h1{font:500 24px/1.1 Georgia,"Times New Roman",serif;margin:0;flex:1;letter-spacing:-.01em}
#pill{display:flex;align-items:center;gap:6px;background:var(--card);border:1px solid var(--line);border-radius:999px;padding:6px 11px;font-size:12px;color:var(--mut);max-width:60%}
#dot{width:8px;height:8px;border-radius:50%;background:var(--bad);flex:none}#dot.connected{background:var(--ok)}#dot.waiting{background:var(--warn)}
#st{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
#msg{display:none;background:var(--accbg);border:1px solid var(--line);border-radius:14px;padding:10px 12px;margin-bottom:12px;color:var(--fg);font-size:13px}
.card{background:var(--card);border:1px solid var(--line);border-radius:20px;padding:12px;margin-bottom:12px}
.lbl{font:500 12px/1 system-ui,sans-serif;color:var(--mut);letter-spacing:.06em;text-transform:uppercase;margin:2px 2px 10px}
.row{display:grid;gap:8px}.r2{grid-template-columns:repeat(2,1fr)}.r3{grid-template-columns:repeat(3,1fr)}.r4{grid-template-columns:repeat(4,1fr)}
.b{display:flex;align-items:center;justify-content:center;background:var(--bg);border:1px solid var(--line);border-radius:14px;min-height:50px;font-weight:600;
  color:var(--fg);padding:0 8px;text-align:center;font-size:14px;transition:transform .06s,background .1s}
.b.on{transform:scale(.95);background:var(--accbg);border-color:var(--acc)}
.b.pri{background:var(--acc);border-color:var(--acc);color:#fff}.b.pri.on{background:var(--accd)}
.b.small{min-height:40px;font-size:13px;border-radius:12px}.b.act{background:var(--accbg);border-color:var(--acc);color:var(--accd)}
.b.red{color:var(--bad)}
#tp{touch-action:none;height:28vh;min-height:170px;border-radius:16px;background:var(--bg);border:1.5px dashed var(--line);display:flex;align-items:center;
  justify-content:center;color:var(--mut);text-align:center;line-height:1.6;font-size:14px}
#tp.on{border-color:var(--acc);background:var(--accbg)}
#dp{display:grid;grid-template-columns:repeat(3,1fr);grid-template-rows:repeat(3,56px);gap:8px}
#dp .b{font-size:20px}#up{grid-area:1/2}#lf{grid-area:2/1}#ok{grid-area:2/2;font-size:16px}#rt{grid-area:2/3}#dn{grid-area:3/2}
.vc{display:grid;grid-template-columns:1fr 1fr;gap:8px}.col{display:grid;gap:8px;grid-template-rows:repeat(3,48px)}
.col .t{display:flex;align-items:center;justify-content:center;color:var(--mut);font-size:11px;letter-spacing:.1em}
#tx{display:flex;gap:8px}
#tx input,.f input{flex:1;min-width:0;-webkit-user-select:text;user-select:text;background:var(--bg);color:var(--fg);border:1px solid var(--line);
  border-radius:14px;padding:0 14px;min-height:50px;font-size:16px;font-family:inherit;outline:none}
#tx input:focus,.f input:focus{border-color:var(--acc)}#tx.live input{border-color:var(--ok);box-shadow:0 0 0 3px rgba(79,138,91,.2)}
.hint{color:var(--mut);font-size:12px;margin:8px 2px 0}
.apps .b{font-size:13px}
details summary{color:var(--mut);font-size:13px;padding:4px 2px;list-style:none}details summary::-webkit-details-marker{display:none}
.f{display:grid;gap:8px;margin-top:10px}.f label{font-size:12px;color:var(--mut);margin-bottom:-4px}
</style></head><body>
<div id="app">
  <header><span class="logo">&#10059;</span><h1>TV Remote</h1><div id="pill"><div id="dot"></div><span id="st">Connecting</span></div></header>
  <div id="msg"></div>

  <div class="card"><div class="row r2">
    <div class="b pri" id="pon">Power on</div><div class="b red" id="poff">Power off</div></div></div>

  <div class="card">
    <div id="tp">Swipe for arrow keys<br>Tap for OK</div>
  </div>

  <div class="card">
    <div id="dp">
      <div class="b" id="up" data-k="KEY_UP" data-rep="1">&#9650;</div>
      <div class="b" id="lf" data-k="KEY_LEFT" data-rep="1">&#9664;</div>
      <div class="b pri" id="ok" data-k="KEY_ENTER">OK</div>
      <div class="b" id="rt" data-k="KEY_RIGHT" data-rep="1">&#9654;</div>
      <div class="b" id="dn" data-k="KEY_DOWN" data-rep="1">&#9660;</div>
    </div>
    <div class="row r4" style="margin-top:8px">
      <div class="b" data-k="KEY_RETURN">Back</div><div class="b" data-k="KEY_HOME">Home</div>
      <div class="b" data-k="KEY_MENU">Menu</div><div class="b" data-k="KEY_SOURCE">Source</div>
    </div>
  </div>

  <div class="card">
    <div class="vc">
      <div class="col"><div class="b" data-k="KEY_VOLUP" data-rep="1">Vol +</div><div class="t">VOLUME</div><div class="b" data-k="KEY_VOLDOWN" data-rep="1">Vol &minus;</div></div>
      <div class="col"><div class="b" data-k="KEY_CHUP" data-rep="1">Ch +</div><div class="t">CHANNEL</div><div class="b" data-k="KEY_CHDOWN" data-rep="1">Ch &minus;</div></div>
    </div>
    <div class="row r3" style="margin-top:8px">
      <div class="b" data-k="KEY_MUTE">Mute</div><div class="b" data-k="KEY_INFO">Info</div><div class="b" data-k="KEY_GUIDE">Guide</div>
    </div>
    <div class="row r4" style="margin-top:8px">
      <div class="b" data-k="KEY_REWIND">&#9194;</div><div class="b" data-k="KEY_PLAY">&#9654;</div>
      <div class="b" data-k="KEY_PAUSE">&#10074;&#10074;</div><div class="b" data-k="KEY_FF">&#9193;</div>
    </div>
  </div>

  <div class="card"><div class="lbl">Apps</div>
    <div class="row r2 apps">
      <div class="b" data-app="111299001912">YouTube</div><div class="b" data-app="3201907018807">Netflix</div>
      <div class="b" data-app="3201910019365">Prime Video</div><div class="b" data-app="3201901017640">Hotstar</div>
    </div>
  </div>

  <div class="card"><div class="lbl">Type on TV</div>
    <div class="row r2" style="margin-bottom:8px"><div class="b small act" id="k_live">Live typing</div><div class="b small" id="k_grid">YouTube keyboard</div></div>
    <div class="row" id="gridrow" style="display:none;margin-bottom:8px"><div class="b small" id="k_reset">TV cursor is on letter A &mdash; start here</div></div>
    <div id="tx"><input id="txt" type="text" placeholder="Type here, it goes to the TV" autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false" enterkeyhint="search">
      <div class="b pri" id="send" style="min-width:76px">Done</div></div>
    <div class="hint" id="txhint"></div>
  </div>

  <div class="card"><details><summary>Number pad</summary><div class="row r3" style="margin-top:8px">
    <div class="b" data-k="KEY_1">1</div><div class="b" data-k="KEY_2">2</div><div class="b" data-k="KEY_3">3</div>
    <div class="b" data-k="KEY_4">4</div><div class="b" data-k="KEY_5">5</div><div class="b" data-k="KEY_6">6</div>
    <div class="b" data-k="KEY_7">7</div><div class="b" data-k="KEY_8">8</div><div class="b" data-k="KEY_9">9</div>
    <div class="b" style="visibility:hidden"></div><div class="b" data-k="KEY_0">0</div><div class="b" data-k="KEY_ENTER">Enter</div>
  </div></details></div>

  <div class="card"><details id="setd"><summary>TV settings</summary>
    <div class="f"><label>TV IP address (TV: Settings &gt; Network)</label><input id="cip" inputmode="decimal" placeholder="192.168.0.101">
      <label>TV MAC address (needed for Power on)</label><input id="cmac" placeholder="AA:BB:CC:DD:EE:FF">
      <div class="b pri" id="csave">Save and reconnect</div><div class="hint" id="cmsg"></div></div>
  </details></div>
</div>
<script>
(function(){
const $=id=>document.getElementById(id);
let ws=null;
function send(o){if(ws&&ws.readyState===1)ws.send(JSON.stringify(o))}
function setState(s,msg){$('dot').className=s;$('st').textContent=s==='connected'?'Connected':s==='waiting'?'Waiting':'Offline';
  const m=$('msg');if(s==='connected'){m.style.display='none'}else{m.style.display='block';m.textContent=msg||''}}
function connect(){
  ws=new WebSocket('ws://'+location.host+'/ws');
  ws.onclose=()=>{setState('offline','Starting up, one moment...');setTimeout(connect,1500)};
  ws.onmessage=e=>{let m;try{m=JSON.parse(e.data)}catch(x){return}if(m.t==='state')setState(m.s,m.msg);else if(m.t==='ime')setIme(m.on,m.text)};
}
connect();
function buzz(n){try{navigator.vibrate&&navigator.vibrate(n||8)}catch(x){}}
$('pill').addEventListener('pointerdown',e=>{e.preventDefault();send({t:'retry'})});
document.querySelectorAll('[data-k]').forEach(el=>{
  const k=el.dataset.k,rep=el.dataset.rep==='1';let t1=null,t2=null;
  const stop=()=>{el.classList.remove('on');clearTimeout(t1);clearInterval(t2);t1=t2=null};
  el.addEventListener('pointerdown',e=>{e.preventDefault();el.classList.add('on');send({t:'key',k});buzz();
    if(rep){t1=setTimeout(()=>{t2=setInterval(()=>send({t:'key',k}),140)},420)}});
  ['pointerup','pointercancel','pointerleave'].forEach(ev=>el.addEventListener(ev,stop));
});
document.querySelectorAll('[data-app]').forEach(el=>{
  el.addEventListener('pointerdown',e=>{e.preventDefault();el.classList.add('on');send({t:'app',id:el.dataset.app});buzz(12)});
  ['pointerup','pointercancel','pointerleave'].forEach(ev=>el.addEventListener(ev,()=>el.classList.remove('on')));
});
$('pon').addEventListener('pointerdown',e=>{e.preventDefault();send({t:'poweron'});buzz(20)});
$('poff').addEventListener('pointerdown',e=>{e.preventDefault();send({t:'poweroff'});buzz(20)});

const tx=$('txt');let prev='',kbmode='live';
function hint(on){$('txhint').textContent=kbmode==='grid'?'Move the TV cursor onto the letter A, tap the button above, then type here. The phone presses the TV keys for you.':
  (on?'TV text box is open: just type, it appears on the TV.':'To type: select the search box on the TV with OK, then type here.')}
function setIme(on,text){$('tx').classList.toggle('live',!!on);hint(on);
  if(on&&kbmode==='live'){tx.value=text||'';prev=tx.value;try{tx.focus()}catch(e){}buzz(30)}}
setIme(false);
function setKb(m){kbmode=m;$('k_live').classList.toggle('act',m==='live');$('k_grid').classList.toggle('act',m==='grid');
  $('gridrow').style.display=m==='grid'?'grid':'none';tx.value='';prev='';hint(false)}
$('k_live').addEventListener('pointerdown',e=>{e.preventDefault();setKb('live')});
$('k_grid').addEventListener('pointerdown',e=>{e.preventDefault();setKb('grid')});
$('k_reset').addEventListener('pointerdown',e=>{e.preventDefault();send({t:'gridreset'});tx.value='';prev='';try{tx.focus()}catch(x){}buzz(15)});
tx.addEventListener('input',()=>{const v=tx.value;
  if(kbmode==='live'){send({t:'text',s:v})}
  else if(v.startsWith(prev)){const add=v.slice(prev.length);if(add)send({t:'grid',s:add})}
  else if(prev.startsWith(v)){send({t:'grid',bs:prev.length-v.length})}
  prev=v});
tx.addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();send({t:'key',k:'KEY_ENTER'})}});
$('send').addEventListener('pointerdown',e=>{e.preventDefault();send({t:'imeend'});tx.value='';prev='';tx.blur()});

const tp=$('tp'),STEP=38;let pid=null,sx=0,sy=0,lx=0,ly=0,ax=0,ay=0,moved=0,t0=0;
tp.addEventListener('pointerdown',e=>{if(pid!==null)return;e.preventDefault();pid=e.pointerId;tp.setPointerCapture(pid);
  sx=lx=e.clientX;sy=ly=e.clientY;ax=ay=0;moved=0;t0=Date.now();tp.classList.add('on')});
tp.addEventListener('pointermove',e=>{if(e.pointerId!==pid)return;
  const dx=e.clientX-lx,dy=e.clientY-ly;lx=e.clientX;ly=e.clientY;moved=Math.max(moved,Math.hypot(lx-sx,ly-sy));
  ax+=dx;ay+=dy;let g=0;
  while(g++<6&&(Math.abs(ax)>=STEP||Math.abs(ay)>=STEP)){
    if(Math.abs(ax)>=Math.abs(ay)){send({t:'key',k:ax>0?'KEY_RIGHT':'KEY_LEFT'});ax-=Math.sign(ax)*STEP;ay*=0.5}
    else{send({t:'key',k:ay>0?'KEY_DOWN':'KEY_UP'});ay-=Math.sign(ay)*STEP;ax*=0.5}
    buzz(5)}});
const tpEnd=e=>{if(e.pointerId!==pid)return;pid=null;tp.classList.remove('on');
  if(e.type==='pointerup'&&moved<10&&Date.now()-t0<350){send({t:'key',k:'KEY_ENTER'});buzz(12)}};
tp.addEventListener('pointerup',tpEnd);tp.addEventListener('pointercancel',tpEnd);

fetch('/api/config').then(r=>r.json()).then(c=>{$('cip').value=c.ip;$('cmac').value=c.mac}).catch(()=>{});
$('csave').addEventListener('pointerdown',e=>{e.preventDefault();
  fetch('/api/config',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({ip:$('cip').value,mac:$('cmac').value})})
  .then(r=>r.json()).then(j=>{$('cmsg').textContent=j.ok?'Saved. Look at the TV and press Allow if asked.':(j.error||'Could not save')}).catch(()=>{$('cmsg').textContent='Could not save'})});
document.addEventListener('contextmenu',e=>e.preventDefault());
})();
</script></body></html>
"""


def main():
    app = web.Application()
    app.router.add_get("/", index)
    app.router.add_get("/manifest.json", manifest)
    app.router.add_get("/icon.svg", icon)
    app.router.add_route("*", "/api/config", config_api)
    app.router.add_get("/ws", ws_handler)
    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)
    print(f"TV Remote running. Open http://localhost:{PORT} on this phone.")
    web.run_app(app, host="127.0.0.1", port=PORT, print=None)


if __name__ == "__main__":
    main()
