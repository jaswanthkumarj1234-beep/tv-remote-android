package com.jaswanth.tvremote;

import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Talks straight to a Samsung Tizen TV over the home Wi-Fi (wss://TV:8002). Finds the TV by itself if its IP changes. */
public class TvClient {

    public interface Listener {
        void onState(String state, String msg);
        void onIme(boolean on, String text);
        void onConfig();
    }

    private static final String[] GRID = {"ABCDEFG", "HIJKLMN", "OPQRSTU", "VWXYZ_'"};
    private static final long KEY_DELAY_MS = 130;
    private static final Pattern KEY_RE = Pattern.compile("^KEY_[A-Z0-9_]{1,24}$");
    private static final Pattern APP_RE = Pattern.compile("^[A-Za-z0-9._-]{1,40}$");

    private final SharedPreferences prefs;
    private final Listener listener;
    private final OkHttpClient http;
    private final OkHttpClient probeHttp;
    private final ExecutorService cmd = Executors.newSingleThreadExecutor();
    private final ExecutorService gridExec = Executors.newSingleThreadExecutor();
    private final ExecutorService linkExec = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService sup = Executors.newSingleThreadScheduledExecutor();
    private final Object connLock = new Object();
    private final AtomicBoolean linkQueued = new AtomicBoolean(false);
    private final AtomicInteger pending = new AtomicInteger(0);

    private volatile WebSocket ws;
    private volatile boolean connected = false;
    private volatile boolean powering = false;
    private volatile boolean forceScan = false;
    private volatile long lastScan = 0;
    private volatile CountDownLatch readyLatch = new CountDownLatch(1);
    private volatile boolean ime = false;
    private volatile String state = "offline";
    private volatile String stateMsg = "Starting...";
    private int gridRow = 0;
    private int gridCol = 0;

    public TvClient(SharedPreferences prefs, Listener listener) {
        this.prefs = prefs;
        this.listener = listener;
        OkHttpClient client;
        try {
            // The TV uses a self-signed certificate. This client only ever talks to the TV on the home network.
            final X509TrustManager trustAll = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{trustAll}, new SecureRandom());
            client = new OkHttpClient.Builder()
                    .sslSocketFactory(sc.getSocketFactory(), trustAll)
                    .hostnameVerifier((host, session) -> true)
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .pingInterval(5, TimeUnit.SECONDS) // notices a dead connection within seconds
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        this.http = client;
        this.probeHttp = client.newBuilder()
                .pingInterval(0, TimeUnit.MILLISECONDS)
                .connectTimeout(1500, TimeUnit.MILLISECONDS)
                .readTimeout(2000, TimeUnit.MILLISECONDS)
                .callTimeout(3000, TimeUnit.MILLISECONDS)
                .build();
        sup.scheduleWithFixedDelay(() -> {
            if (!connected && !powering) {
                requestConnect();
            }
        }, 1, 6, TimeUnit.SECONDS);
    }

    // ---------------- state ----------------
    private void setState(String s, String m) {
        state = s;
        stateMsg = m;
        if (!"connected".equals(s)) {
            ime = false;
        }
        listener.onState(s, m);
    }

    public void snapshot() {
        listener.onState(state, stateMsg);
        listener.onIme(ime, "");
    }

    // ---------------- finding the TV ----------------
    private String[] probe(String host) {
        if (!Net.portOpen(host, 8002, 400)) {
            return null;
        }
        try (Response r = probeHttp.newCall(new Request.Builder().url("https://" + host + ":8002/api/v2/").build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) {
                return null;
            }
            JSONObject dev = new JSONObject(r.body().string()).optJSONObject("device");
            if (dev == null || !dev.optString("type", "").contains("Samsung")) {
                return null;
            }
            return new String[]{host, dev.optString("wifiMac", "")};
        } catch (Exception e) {
            return null;
        }
    }

    private String discover(String wantMac) {
        String[] li = Net.localInfo();
        if (li == null) {
            return null;
        }
        lastScan = System.currentTimeMillis();
        setState("offline", "Looking for the TV on Wi-Fi...");
        String want = Net.norm(wantMac);
        List<String[]> hits = Net.scan(li[0], this::probe);
        for (String[] h : hits) {
            if (!want.isEmpty() && Net.norm(h[1]).equals(want)) {
                return h[0];
            }
        }
        return (want.isEmpty() && !hits.isEmpty()) ? hits.get(0)[0] : null;
    }

    private String resolveIp() {
        String ip = prefs.getString("ip", "");
        String mac = prefs.getString("mac", "");
        if (!ip.isEmpty() && Net.portOpen(ip, 8002, 1500)) {
            return ip;
        }
        long now = System.currentTimeMillis();
        if (forceScan || now - lastScan > 30000) {
            forceScan = false;
            String found = discover(mac);
            if (found != null) {
                if (!found.equals(ip)) {
                    prefs.edit().putString("ip", found).apply();
                    listener.onConfig();
                }
                return found;
            }
        }
        setState("offline", "TV is off or not on this Wi-Fi. Tap Power on, or switch it on with its remote.");
        return null;
    }

    private void learnMac(final String ip) {
        new Thread(() -> {
            String[] r = probe(ip);
            SharedPreferences.Editor e = prefs.edit();
            boolean changed = false;
            if (!ip.equals(prefs.getString("ip", ""))) {
                e.putString("ip", ip);
                changed = true;
            }
            if (r != null && !r[1].isEmpty() && !Net.norm(r[1]).equals(Net.norm(prefs.getString("mac", "")))) {
                e.putString("mac", r[1].toUpperCase());
                changed = true;
            }
            if (changed) {
                e.apply();
                listener.onConfig();
            }
        }).start();
    }

    // ---------------- connection ----------------
    private void requestConnect() {
        if (linkQueued.compareAndSet(false, true)) {
            linkExec.execute(() -> {
                try {
                    connect();
                } finally {
                    linkQueued.set(false);
                }
            });
        }
    }

    private boolean connect() {
        synchronized (connLock) {
            if (connected) {
                return true;
            }
            String ip = resolveIp();
            if (ip == null) {
                return false;
            }
            String name = Base64.encodeToString("AndroidPad".getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            for (int attempt = 1; attempt <= 2; attempt++) {
                final String tok = prefs.getString("token", "");
                String url = "wss://" + ip + ":8002/api/v2/channels/samsung.remote.control?name=" + name;
                if (!tok.isEmpty()) {
                    url += "&token=" + tok;
                }
                final CountDownLatch latch = new CountDownLatch(1);
                final CountDownLatch ready = new CountDownLatch(1);
                readyLatch = ready;
                final AtomicInteger result = new AtomicInteger(0); // 1 ok, 2 refused, 3 failed
                final boolean[] opened = {false};
                WebSocket sock;
                try {
                    sock = http.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
                        @Override public void onOpen(WebSocket w, Response r) {
                            opened[0] = true;
                            setState("waiting", tok.isEmpty() ? "Press ALLOW on the TV using its own remote" : "Connecting to the TV...");
                        }
                        @Override public void onMessage(WebSocket w, String text) {
                            handleMessage(text, result, latch);
                        }
                        @Override public void onFailure(WebSocket w, Throwable t, Response r) {
                            onSockEnd(w, result, latch);
                        }
                        @Override public void onClosed(WebSocket w, int code, String reason) {
                            onSockEnd(w, result, latch);
                        }
                    });
                } catch (Exception e) {
                    setState("offline", "Could not reach the TV");
                    return false;
                }
                boolean done;
                try {
                    done = latch.await(tok.isEmpty() ? 70 : 12, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    sock.cancel();
                    return false;
                }
                if (done && result.get() == 1) {
                    ws = sock;
                    connected = true;
                    try {
                        ready.await(1500, TimeUnit.MILLISECONDS); // some TVs send "ready" a moment later
                    } catch (InterruptedException ignored) {
                    }
                    if (connected) {
                        setState("connected", "TV connected");
                        learnMac(ip);
                    }
                    return connected;
                }
                sock.cancel();
                if (!opened[0]) {
                    setState("offline", "TV not reachable - is it on, and on the same Wi-Fi?");
                    return false;
                }
                if (!tok.isEmpty() && attempt == 1) {
                    prefs.edit().remove("token").apply(); // stale token: ask the TV again
                    continue;
                }
                setState("offline", result.get() == 2 || tok.isEmpty()
                        ? "TV refused - Allow was not pressed" : "TV not reachable");
                return false;
            }
            return false;
        }
    }

    private void handleMessage(String text, AtomicInteger result, CountDownLatch latch) {
        try {
            JSONObject d = new JSONObject(text);
            String ev = d.optString("event", "");
            if (result.get() == 0) {
                if ("ms.channel.connect".equals(ev)) {
                    JSONObject data = d.optJSONObject("data");
                    String nt = data == null ? "" : data.optString("token", "");
                    if (!nt.isEmpty()) {
                        prefs.edit().putString("token", nt).apply();
                    }
                    result.set(1);
                    latch.countDown();
                } else if ("ms.channel.unauthorized".equals(ev) || "ms.channel.timeOut".equals(ev)) {
                    result.set(2);
                    latch.countDown();
                }
                return;
            }
            if (result.get() != 1) {
                return;
            }
            if ("ms.channel.ready".equals(ev)) {
                readyLatch.countDown();
            } else if ("ms.remote.imeStart".equals(ev)) {
                ime = true;
                listener.onIme(true, decode(d.optString("data", "")));
            } else if ("ms.remote.imeUpdate".equals(ev)) {
                if (!ime) {
                    ime = true;
                    listener.onIme(true, decode(d.optString("data", "")));
                }
            } else if ("ms.remote.imeEnd".equals(ev) || "ms.remote.imeDone".equals(ev)) {
                ime = false;
                listener.onIme(false, "");
            }
        } catch (Exception ignored) {
        }
    }

    private void onSockEnd(WebSocket w, AtomicInteger result, CountDownLatch latch) {
        if (result.get() == 0) {
            result.set(3);
            latch.countDown();
        } else if (ws == w) {
            ws = null;
            connected = false;
            setState("offline", "TV connection lost - reconnecting...");
            requestConnect();
        }
    }

    private static String decode(String b64) {
        if (b64 == null || b64.isEmpty()) {
            return "";
        }
        try {
            return new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private void drop() {
        WebSocket w = ws;
        ws = null;
        connected = false;
        if (w != null) {
            try {
                w.cancel();
            } catch (Exception ignored) {
            }
        }
        if (!"offline".equals(state)) {
            setState("offline", "TV connection lost - reconnecting...");
        }
    }

    /** Never waits: if the TV is not connected the key is dropped (no old commands replaying later). */
    private boolean send(JSONObject payload) {
        WebSocket w = ws;
        if (!connected || w == null) {
            requestConnect();
            return false;
        }
        if (w.queueSize() > 4096 || !w.send(payload.toString())) {
            drop();
            requestConnect();
            return false;
        }
        return true;
    }

    private JSONObject remote(String c, String data, String type) {
        try {
            JSONObject params = new JSONObject()
                    .put("Cmd", c).put("DataOfCmd", data).put("Option", "false").put("TypeOfRemote", type);
            return new JSONObject().put("method", "ms.remote.control").put("params", params);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void sendKey(String k) {
        send(remote("Click", k, "SendRemoteKey"));
    }

    // ---------------- commands ----------------
    public void retry() {
        forceScan = true;
        requestConnect();
    }

    public void resume() {
        if (!connected) {
            requestConnect();
        }
    }

    public void key(final String k) {
        if (!KEY_RE.matcher(k).matches()) {
            return;
        }
        if (pending.incrementAndGet() > 8) { // TV is slow: skip instead of building a backlog
            pending.decrementAndGet();
            return;
        }
        cmd.execute(() -> {
            try {
                sendKey(k);
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    // ---------------- mouse pointer (coalesced, so it stays smooth and never lags) ----------------
    private final AtomicInteger movX = new AtomicInteger(0);
    private final AtomicInteger movY = new AtomicInteger(0);
    private final AtomicInteger moveQueued = new AtomicInteger(0);
    private volatile long lastMove = 0;

    private JSONObject moveMsg(int dx, int dy) {
        try {
            JSONObject pos = new JSONObject().put("x", dx).put("y", dy)
                    .put("Time", String.valueOf(System.currentTimeMillis()));
            JSONObject params = new JSONObject().put("Cmd", "Move").put("Position", pos)
                    .put("TypeOfRemote", "ProcessMouseDevice");
            return new JSONObject().put("method", "ms.remote.control").put("params", params);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** The TV shows its pointer after it receives a couple of zero moves. */
    private void wakePointer() {
        for (int i = 0; i < 2; i++) {
            send(moveMsg(0, 0));
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
        }
        lastMove = System.currentTimeMillis();
    }

    public void mouseWake() {
        if (!connected) {
            return;
        }
        cmd.execute(this::wakePointer);
    }

    /** Relative pointer move. Moves that arrive while one is being sent are merged into the next one. */
    public void move(int dx, int dy) {
        if (!connected) {
            return;
        }
        movX.addAndGet(dx);
        movY.addAndGet(dy);
        if (moveQueued.getAndSet(1) == 0) {
            cmd.execute(() -> {
                try {
                    int x = Math.max(-300, Math.min(300, movX.getAndSet(0)));
                    int y = Math.max(-300, Math.min(300, movY.getAndSet(0)));
                    if (x == 0 && y == 0) {
                        return;
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastMove > 3000) {
                        wakePointer();
                    }
                    lastMove = now;
                    send(moveMsg(x, y));
                } finally {
                    moveQueued.set(0);
                    if (movX.get() != 0 || movY.get() != 0) {
                        move(0, 0);
                    }
                }
            });
        }
    }

    public void mouseClick(final boolean right) {
        cmd.execute(() -> {
            try {
                JSONObject params = new JSONObject()
                        .put("Cmd", right ? "RightClick" : "LeftClick")
                        .put("TypeOfRemote", "ProcessMouseDevice");
                send(new JSONObject().put("method", "ms.remote.control").put("params", params));
            } catch (Exception ignored) {
            }
        });
    }

    public void text(String s) {
        final String t = s.length() > 200 ? s.substring(0, 200) : s;
        cmd.execute(() -> send(remote(
                Base64.encodeToString(t.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP), "base64", "SendInputString")));
    }

    public void imeEnd() {
        cmd.execute(() -> send(remote("", "base64", "SendInputEnd")));
    }

    public void app(final String id) {
        if (!APP_RE.matcher(id).matches()) {
            return;
        }
        cmd.execute(() -> {
            try {
                JSONObject data = new JSONObject().put("action_type", "DEEP_LINK").put("appId", id);
                JSONObject params = new JSONObject().put("event", "ed.apps.launch").put("to", "host").put("data", data);
                send(new JSONObject().put("method", "ms.channel.emit").put("params", params));
            } catch (Exception ignored) {
            }
        });
    }

    public void powerOff() {
        cmd.execute(() -> {
            if (connected) {
                sendKey("KEY_POWER");
            }
        });
    }

    /** Wake-on-LAN, then wait for the TV to come up (even if its IP changed) and connect. */
    public void powerOn() {
        forceScan = true;
        linkExec.execute(() -> {
            if (connected) {
                return;
            }
            String mac = prefs.getString("mac", "");
            if (mac.isEmpty()) {
                setState("offline", "Power on needs the TV's MAC. Switch the TV on once and open this app: it learns the MAC by itself.");
                return;
            }
            powering = true;
            try {
                setState("waiting", "Sending wake signal to the TV...");
                for (int i = 0; i < 14; i++) {
                    wakeOnLan(mac, prefs.getString("ip", ""));
                    try {
                        Thread.sleep(2500);
                    } catch (InterruptedException e) {
                        return;
                    }
                    String ip = prefs.getString("ip", "");
                    boolean up = !ip.isEmpty() && Net.portOpen(ip, 8002, 800);
                    if (!up) {
                        String f = discover(mac);
                        if (f != null) {
                            prefs.edit().putString("ip", f).apply();
                            listener.onConfig();
                            up = true;
                        }
                    }
                    if (up) {
                        setState("waiting", "TV is starting...");
                        if (connect()) {
                            return;
                        }
                    }
                }
                setState("offline", "The TV did not wake. On the TV turn on Settings > General > Network > Expert Settings > Power On with Mobile, then try again.");
            } finally {
                powering = false;
            }
        });
    }

    private static void wakeOnLan(String mac, String ip) {
        try {
            String hex = mac.replace(":", "").replace("-", "");
            byte[] m = new byte[6];
            for (int i = 0; i < 6; i++) {
                m[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            byte[] pkt = new byte[6 + 16 * 6];
            for (int i = 0; i < 6; i++) {
                pkt[i] = (byte) 0xFF;
            }
            for (int r = 0; r < 16; r++) {
                System.arraycopy(m, 0, pkt, 6 + r * 6, 6);
            }
            List<String> targets = new ArrayList<>();
            String[] li = Net.localInfo();
            if (li != null && li[1] != null) {
                targets.add(li[1]);
            }
            targets.add("255.255.255.255");
            if (!ip.isEmpty() && ip.lastIndexOf('.') > 0) {
                targets.add(ip.substring(0, ip.lastIndexOf('.')) + ".255");
                targets.add(ip);
            }
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                for (int rep = 0; rep < 3; rep++) {
                    for (String t : targets) {
                        for (int p : new int[]{9, 7}) {
                            try {
                                s.send(new DatagramPacket(pkt, pkt.length, InetAddress.getByName(t), p));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    Thread.sleep(80);
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------- YouTube grid keyboard ----------------
    public void gridReset() {
        gridExec.execute(() -> {
            gridRow = 0;
            gridCol = 0;
        });
    }

    private List<String> gridPath(String target) {
        int r = gridRow;
        int c = gridCol;
        List<String> keys = new ArrayList<>();
        int tr = -1;
        int tc = -1;
        if (target.equals("SPACE")) {
            tr = 3;
            tc = 0;
        } else if (target.equals("BACK")) {
            tr = 0;
            tc = 7;
        } else {
            for (int i = 0; i < GRID.length; i++) {
                int j = GRID[i].indexOf(target);
                if (j >= 0) {
                    tr = i;
                    tc = j;
                }
            }
            if (tr < 0) {
                return null;
            }
        }
        if (r == 4) {
            keys.add("KEY_UP");
            r = 3;
            c = 0;
        }
        if (tc == 7) {
            while (r > tr) { keys.add("KEY_UP"); r--; }
            while (r < tr) { keys.add("KEY_DOWN"); r++; }
            while (c < 7) { keys.add("KEY_RIGHT"); c++; }
        } else {
            if (c == 7) { keys.add("KEY_LEFT"); c = 6; }
            while (c < tc) { keys.add("KEY_RIGHT"); c++; }
            while (c > tc) { keys.add("KEY_LEFT"); c--; }
            while (r < tr) { keys.add("KEY_DOWN"); r++; }
            while (r > tr) { keys.add("KEY_UP"); r--; }
        }
        if (target.equals("SPACE")) {
            keys.add("KEY_DOWN");
            r = 4;
            c = 0;
        }
        gridRow = r;
        gridCol = c;
        return keys;
    }

    private void press(List<String> keys) {
        for (String k : keys) {
            sendKey(k);
            try {
                Thread.sleep(KEY_DELAY_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public void gridType(final String s) {
        gridExec.execute(() -> {
            String str = s.length() > 60 ? s.substring(0, 60) : s;
            for (int i = 0; i < str.length(); i++) {
                char ch = str.charAt(i);
                String target = ch == ' ' ? "SPACE" : String.valueOf(Character.toUpperCase(ch));
                List<String> keys = gridPath(target);
                if (keys == null) {
                    continue;
                }
                keys.add("KEY_ENTER");
                press(keys);
            }
        });
    }

    public void gridBackspace(final int n) {
        gridExec.execute(() -> {
            int count = Math.max(0, Math.min(n, 40));
            for (int i = 0; i < count; i++) {
                List<String> keys = gridPath("BACK");
                if (keys != null) {
                    keys.add("KEY_ENTER");
                    press(keys);
                }
            }
        });
    }

    // ---------------- settings ----------------
    public String getConfigJson() {
        try {
            return new JSONObject().put("ip", prefs.getString("ip", "")).put("mac", prefs.getString("mac", "")).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Both fields are optional now (the app finds the TV itself). Returns "" on success, otherwise an error message. */
    public String saveConfig(String ip, String mac) {
        ip = ip.trim();
        mac = mac.trim();
        if (!ip.isEmpty() && !ip.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            return "Check the IP address (like 192.168.0.101) or leave it empty";
        }
        if (!mac.isEmpty() && !mac.matches("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")) {
            return "Check the MAC address (like AA:BB:CC:DD:EE:FF) or leave it empty";
        }
        SharedPreferences.Editor e = prefs.edit();
        if (!ip.equals(prefs.getString("ip", ""))) {
            e.remove("token");
        }
        e.putString("ip", ip);
        if (!mac.isEmpty()) {
            e.putString("mac", mac.toUpperCase().replace('-', ':'));
        }
        e.apply();
        forceScan = true;
        linkExec.execute(() -> {
            drop();
            connect();
        });
        return "";
    }

    public void shutdown() {
        sup.shutdownNow();
        cmd.shutdownNow();
        gridExec.shutdownNow();
        linkExec.shutdownNow();
        drop();
    }
}
