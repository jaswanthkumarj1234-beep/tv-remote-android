package com.jaswanth.tvremote;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Controls the Windows laptop running LaptopPad (ws://laptop:8780). PIN login, finds the laptop by itself. */
public class LaptopClient {

    public interface Listener {
        void onState(String state, String msg);
        void onConfig();
    }

    private static final int PORT = 8780;

    private final SharedPreferences prefs;
    private final Listener listener;
    private final OkHttpClient http;
    private final OkHttpClient probeHttp;
    private final ExecutorService linkExec = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService sup = Executors.newSingleThreadScheduledExecutor();
    private final Object connLock = new Object();
    private final AtomicBoolean linkQueued = new AtomicBoolean(false);

    private volatile WebSocket ws;
    private volatile boolean connected = false;
    private volatile boolean forceScan = false;
    private volatile long lastScan = 0;
    private volatile String badPin = "";
    private volatile String state = "offline";
    private volatile String stateMsg = "Not set up";

    public LaptopClient(SharedPreferences prefs, Listener listener) {
        this.prefs = prefs;
        this.listener = listener;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(5, TimeUnit.SECONDS)
                .build();
        this.probeHttp = http.newBuilder()
                .pingInterval(0, TimeUnit.MILLISECONDS)
                .readTimeout(2000, TimeUnit.MILLISECONDS)
                .callTimeout(3000, TimeUnit.MILLISECONDS)
                .build();
        sup.scheduleWithFixedDelay(() -> {
            if (!connected && !prefs.getString("lpin", "").isEmpty()) {
                requestConnect();
            }
        }, 2, 7, TimeUnit.SECONDS);
    }

    private void setState(String s, String m) {
        state = s;
        stateMsg = m;
        listener.onState(s, m);
    }

    public void snapshot() {
        listener.onState(state, stateMsg);
    }

    private static String hmac(String pin, String nonce) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(pin.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] r = m.doFinal(nonce.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : r) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String[] probe(String host) {
        if (!Net.portOpen(host, PORT, 350)) {
            return null;
        }
        try (Response r = probeHttp.newCall(new Request.Builder().url("http://" + host + ":" + PORT + "/hello").build()).execute()) {
            if (!r.isSuccessful() || r.body() == null) {
                return null;
            }
            JSONObject d = new JSONObject(r.body().string());
            return "laptoppad".equals(d.optString("app", "")) ? new String[]{host} : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveIp() {
        String ip = prefs.getString("lip", "");
        if (!ip.isEmpty() && Net.portOpen(ip, PORT, 1200)) {
            return ip;
        }
        long now = System.currentTimeMillis();
        if (forceScan || now - lastScan > 30000) {
            forceScan = false;
            lastScan = now;
            String[] li = Net.localInfo();
            if (li != null) {
                setState("offline", "Looking for the laptop on Wi-Fi...");
                List<String[]> hits = Net.scan(li[0], this::probe);
                if (!hits.isEmpty()) {
                    String found = hits.get(0)[0];
                    if (!found.equals(ip)) {
                        prefs.edit().putString("lip", found).apply();
                        listener.onConfig();
                    }
                    return found;
                }
            }
        }
        setState("offline", "Laptop not found. Is it on, with LaptopPad running, on the same Wi-Fi?");
        return null;
    }

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
            final String pin = prefs.getString("lpin", "");
            if (pin.isEmpty()) {
                setState("offline", "Type the PIN from the laptop in Laptop settings below");
                return false;
            }
            if (pin.equals(badPin)) {
                setState("offline", "Wrong PIN. The PIN is in the file pin.txt in the LaptopPad folder on the laptop.");
                return false;
            }
            String ip = resolveIp();
            if (ip == null) {
                return false;
            }
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicInteger result = new AtomicInteger(0); // 1 ok, 2 wrong pin, 3 failed
            final String[] nameBox = {""};
            WebSocket sock;
            try {
                sock = http.newWebSocket(new Request.Builder().url("ws://" + ip + ":" + PORT + "/ws").build(), new WebSocketListener() {
                    @Override public void onMessage(WebSocket w, String text) {
                        try {
                            JSONObject d = new JSONObject(text);
                            String t = d.optString("t", "");
                            if ("hello".equals(t)) {
                                w.send(new JSONObject().put("t", "auth").put("h", hmac(pin, d.optString("nonce", ""))).toString());
                            } else if ("ok".equals(t)) {
                                nameBox[0] = d.optString("name", "");
                                result.set(1);
                                latch.countDown();
                            } else if ("bad".equals(t)) {
                                result.set(2);
                                latch.countDown();
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    @Override public void onFailure(WebSocket w, Throwable t, Response r) {
                        onEnd(w, result, latch);
                    }
                    @Override public void onClosed(WebSocket w, int code, String reason) {
                        onEnd(w, result, latch);
                    }
                });
            } catch (Exception e) {
                setState("offline", "Could not reach the laptop");
                return false;
            }
            boolean done;
            try {
                done = latch.await(8, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                sock.cancel();
                return false;
            }
            if (done && result.get() == 1) {
                ws = sock;
                connected = true;
                setState("connected", "Laptop connected" + (nameBox[0].isEmpty() ? "" : ": " + nameBox[0]));
                return true;
            }
            sock.cancel();
            if (result.get() == 2) {
                badPin = pin;
                setState("offline", "Wrong PIN. The PIN is in the file pin.txt in the LaptopPad folder on the laptop.");
            } else {
                setState("offline", "Laptop not reachable. Is it on, with LaptopPad running?");
            }
            return false;
        }
    }

    private void onEnd(WebSocket w, AtomicInteger result, CountDownLatch latch) {
        if (result.get() == 0) {
            result.set(3);
            latch.countDown();
        } else if (ws == w) {
            ws = null;
            connected = false;
            setState("offline", "Laptop connection lost - reconnecting...");
            requestConnect();
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
    }

    /** Sends one command (already a JSON string). Never waits; dropped if not connected. */
    public void cmd(String json) {
        WebSocket w = ws;
        if (!connected || w == null) {
            requestConnect();
            return;
        }
        if (w.queueSize() > 200000 || !w.send(json)) {
            drop();
            setState("offline", "Laptop connection lost - reconnecting...");
            requestConnect();
        }
    }

    public void retry() {
        badPin = "";
        forceScan = true;
        requestConnect();
    }

    public void resume() {
        if (!connected) {
            requestConnect();
        }
    }

    public String getConfigJson() {
        try {
            return new JSONObject().put("ip", prefs.getString("lip", "")).put("pin", prefs.getString("lpin", "")).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** IP is optional (found automatically). Returns "" on success, otherwise an error message. */
    public String saveConfig(String ip, String pin) {
        ip = ip.trim();
        pin = pin.trim();
        if (!ip.isEmpty() && !ip.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            return "Check the laptop IP (like 192.168.0.104) or leave it empty";
        }
        if (!pin.matches("^\\d{4,8}$")) {
            return "The PIN is 4 to 8 digits (see pin.txt on the laptop)";
        }
        prefs.edit().putString("lip", ip).putString("lpin", pin).apply();
        badPin = "";
        forceScan = true;
        linkExec.execute(() -> {
            drop();
            connect();
        });
        return "";
    }

    public void shutdown() {
        sup.shutdownNow();
        linkExec.shutdownNow();
        drop();
    }
}
