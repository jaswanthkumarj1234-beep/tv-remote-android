package com.jaswanth.tvremote;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Small LAN helpers shared by the TV and laptop clients. */
public final class Net {

    public interface Probe {
        String[] test(String host);
    }

    private Net() { }

    public static boolean portOpen(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns {"192.168.0.", directedBroadcast} for the Wi-Fi interface, or null. */
    public static String[] localInfo() {
        try {
            String[] best = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) {
                        continue;
                    }
                    String h = a.getHostAddress();
                    InetAddress b = ia.getBroadcast();
                    String[] cand = {h.substring(0, h.lastIndexOf('.') + 1), b == null ? null : b.getHostAddress()};
                    if (ni.getName().startsWith("wlan")) {
                        return cand;
                    }
                    if (best == null) {
                        best = cand;
                    }
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    public static String norm(String mac) {
        return mac == null ? "" : mac.replace(":", "").replace("-", "").trim().toUpperCase(Locale.ROOT);
    }

    /** Tries every host x.x.x.1-254 in parallel; returns what the probe returned for each hit. */
    public static List<String[]> scan(String prefix, final Probe p) {
        ExecutorService pool = Executors.newFixedThreadPool(48);
        List<Future<String[]>> fs = new ArrayList<>();
        for (int i = 1; i < 255; i++) {
            final String h = prefix + i;
            fs.add(pool.submit(() -> p.test(h)));
        }
        List<String[]> out = new ArrayList<>();
        for (Future<String[]> f : fs) {
            try {
                String[] r = f.get(20, TimeUnit.SECONDS);
                if (r != null) {
                    out.add(r);
                }
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
        return out;
    }
}
