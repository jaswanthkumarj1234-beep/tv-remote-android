package com.jaswanth.tvremote;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

public class MainActivity extends Activity {

    private WebView web;
    private TvClient tv;
    private LaptopClient pc;
    private Vibrator vib;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int bg = night ? 0xFF262624 : 0xFFF5F4EE;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (!night && Build.VERSION.SDK_INT >= 23) {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }

        vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        SharedPreferences prefs = getSharedPreferences("tvremote", MODE_PRIVATE);
        tv = new TvClient(prefs, new TvClient.Listener() {
            @Override public void onState(String state, String msg) {
                try {
                    push(new JSONObject().put("t", "state").put("s", state).put("msg", msg));
                } catch (Exception ignored) {
                }
            }
            @Override public void onIme(boolean on, String text) {
                try {
                    push(new JSONObject().put("t", "ime").put("on", on).put("text", text));
                } catch (Exception ignored) {
                }
            }
            @Override public void onConfig() {
                try {
                    push(new JSONObject(tv.getConfigJson()).put("t", "cfg"));
                } catch (Exception ignored) {
                }
            }
        });
        pc = new LaptopClient(prefs, new LaptopClient.Listener() {
            @Override public void onState(String state, String msg) {
                try {
                    push(new JSONObject().put("t", "pc").put("s", state).put("msg", msg));
                } catch (Exception ignored) {
                }
            }
            @Override public void onConfig() {
                try {
                    push(new JSONObject(pc.getConfigJson()).put("t", "lcfg"));
                } catch (Exception ignored) {
                }
            }
        });

        web = new WebView(this);
        web.setBackgroundColor(bg);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccessFromFileURLs(false);
        ws.setAllowUniversalAccessFromFileURLs(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        ws.setSupportZoom(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("file:///android_asset/");
            }
            @Override public void onPageStarted(WebView v, String url, Bitmap favicon) {
                super.onPageStarted(v, url, favicon);
            }
        });
        web.addJavascriptInterface(new Bridge(), "Bridge");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private void push(final JSONObject o) {
        runOnUiThread(() -> {
            if (web != null) {
                web.evaluateJavascript("window.onNative&&window.onNative(" + o.toString() + ")", null);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tv != null) {
            tv.resume();
        }
        if (pc != null) {
            pc.resume();
        }
    }

    @Override
    protected void onDestroy() {
        if (tv != null) {
            tv.shutdown();
        }
        if (pc != null) {
            pc.shutdown();
        }
        super.onDestroy();
    }

    /** Called from the page's JavaScript. Only whitelisted commands are forwarded. */
    public class Bridge {
        @JavascriptInterface
        public void send(String json) {
            try {
                JSONObject d = new JSONObject(json);
                String t = d.optString("t", "");
                switch (t) {
                    case "key": tv.key(d.optString("k", "")); break;
                    case "text": tv.text(d.optString("s", "")); break;
                    case "app": tv.app(d.optString("id", "")); break;
                    case "grid":
                        if (d.has("bs")) {
                            tv.gridBackspace(d.optInt("bs", 0));
                        } else {
                            tv.gridType(d.optString("s", ""));
                        }
                        break;
                    case "gridreset": tv.gridReset(); break;
                    case "imeend": tv.imeEnd(); break;
                    case "move": tv.move(d.optInt("dx", 0), d.optInt("dy", 0)); break;
                    case "click": tv.mouseClick("right".equals(d.optString("b", "left"))); break;
                    case "mousewake": tv.mouseWake(); break;
                    case "poweron": tv.powerOn(); break;
                    case "poweroff": tv.powerOff(); break;
                    case "retry": tv.retry(); break;
                    case "lretry": pc.retry(); break;
                    case "lmv": case "lclick": case "lscroll": case "lkey":
                    case "lhot": case "ltext": case "lmedia": case "lsys":
                        d.put("t", t.substring(1));
                        pc.cmd(d.toString());
                        break;
                    default: break;
                }
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void ready() {
            tv.snapshot();
            pc.snapshot();
        }

        @JavascriptInterface
        public String getConfig() {
            return tv.getConfigJson();
        }

        @JavascriptInterface
        public String saveConfig(String ip, String mac) {
            return tv.saveConfig(ip, mac);
        }

        @JavascriptInterface
        public String getLaptopConfig() {
            return pc.getConfigJson();
        }

        @JavascriptInterface
        public String saveLaptopConfig(String ip, String pin) {
            return pc.saveConfig(ip, pin);
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            try {
                if (vib == null || !vib.hasVibrator()) {
                    return;
                }
                int len = Math.max(5, Math.min(ms, 60));
                if (Build.VERSION.SDK_INT >= 26) {
                    vib.vibrate(VibrationEffect.createOneShot(len, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vib.vibrate(len);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
