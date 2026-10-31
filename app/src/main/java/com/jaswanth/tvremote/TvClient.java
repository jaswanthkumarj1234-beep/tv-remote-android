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

/** Talks straight to a Samsung Tizen TV over the home Wi-Fi (wss://TV:8002). */
public class TvClient {

    public interface Listener {
        void onState(String state, String msg);
        void onIme(boolean on, String text);
    }

    private static final String[] GRID = {"ABCDEFG", "HIJKLMN", "OPQRSTU", "VWXYZ_'"};
    private static final long KEY_DELAY_MS = 130;
    private static final Pattern KEY_RE = Pattern.compile("^KEY_[A-Z0-9_]{1,24}$");
    private static final Pattern APP_RE = Pattern.compile("^[A-Za-z0-9._-]{1,40}$");

    private final SharedPreferences prefs;
    private final Listener listener;
    private final OkHttpClient http;
    private final ExecutorService cmd = Executors.newSingleThreadExecutor();
    private final ExecutorService gridExec = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService sup = Executors.newSingleThreadScheduledExecutor();
    private final Object connLock = new Object();

    private volatile WebSocket ws;
    private volatile boolean connected = false;
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
            // The TV uses a self-signed certificate. This client only ever talks to the TV address the user typed in.
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
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        this.http = client;
        sup.scheduleWithFixedDelay(() -> {
            if (!connected) {
                cmd.execute(() -> connect());
            }
        }, 1, 15, TimeUnit.SECONDS);
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

    // ---------------- connection ----------------
    public boolean connect() {
        synchronized (connLock) {
            if (connected) {
                return true;
            }
            String ip = prefs.getString("ip", "");
            if (ip.isEmpty()) {
                setState("offline", "First time: open TV settings below and enter your TV IP address");
                return false;
            }
            String name = Base64.encodeToString("AndroidPad".getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            for (int attempt = 1; attempt <= 2; attempt++) {
                String tok = prefs.getString("token", "");
                String url = "wss://" + ip + ":8002/api/v2/channels/samsung.remote.control?name=" + name;
                if (!tok.isEmpty()) {
                    url += "&token=" + tok;
                }
                final CountDownLatch latch = new CountDownLatch(1);
                final AtomicInteger result = new AtomicInteger(0); // 1 ok, 2 refused, 3 failed
                final boolean[] opened = {false};
                WebSocket sock;
                try {
                    sock = http.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
                        @Override public void onOpen(WebSocket w, Response r) {
                            opened[0] = true;
                            setState("waiting", "Press ALLOW on the TV using its own remote");
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
                    setState("offline", "Check the TV IP address in TV settings");
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
                    setState("connected", "TV connected");
                    return true;
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
            if ("ms.remote.imeStart".equals(ev)) {
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
            setState("offline", "TV not reachable");
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
            setState("offline", "TV not reachable");
        }
    }

    private boolean send(JSONObject payload) {
        for (int i = 0; i < 2; i++) {
            if (!connect()) {
                return false;
            }
            WebSocket w = ws;
            if (w != null && w.send(payload.toString())) {
                return true;
            }
            drop();
        }
        return false;
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
        cmd.execute(() -> connect());
    }

    public void key(final String k) {
        if (!KEY_RE.matcher(k).matches()) {
            return;
        }
        cmd.execute(() -> sendKey(k));
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

    public void powerOn() {
        cmd.execute(() -> {
            String ip = prefs.getString("ip", "");
            String mac = prefs.getString("mac", "");
            if (ip.isEmpty() || mac.isEmpty()) {
                setState("offline", "Power on needs the TV IP and MAC in TV settings");
                return;
            }
            wakeOnLan(mac, ip);
            setState("offline", "Wake signal sent - give the TV about 10 seconds");
            for (int i = 0; i < 8; i++) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    return;
                }
                if (connect()) {
                    return;
                }
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
            String[] targets = {"255.255.255.255", ip.substring(0, ip.lastIndexOf('.')) + ".255", ip};
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                for (String t : targets) {
                    for (int p : new int[]{9, 7}) {
                        try {
                            s.send(new DatagramPacket(pkt, pkt.length, InetAddress.getByName(t), p));
                        } catch (Exception ignored) {
                        }
                    }
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

    /** Returns "" on success, otherwise an error message. */
    public String saveConfig(String ip, String mac) {
        ip = ip.trim();
        mac = mac.trim();
        if (!ip.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            return "Check the IP address (like 192.168.0.101)";
        }
        if (!mac.isEmpty() && !mac.matches("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")) {
            return "Check the MAC address (like AA:BB:CC:DD:EE:FF)";
        }
        SharedPreferences.Editor e = prefs.edit().putString("ip", ip).remove("token");
        if (!mac.isEmpty()) {
            e.putString("mac", mac.toUpperCase().replace('-', ':'));
        }
        e.apply();
        cmd.execute(() -> {
            drop();
            connect();
        });
        return "";
    }

    public void shutdown() {
        sup.shutdownNow();
        cmd.shutdownNow();
        gridExec.shutdownNow();
        drop();
    }
}
