package de.robsen.sentinel;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import org.json.JSONObject;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.app.PictureInPictureParams;
import android.util.Rational;
import android.view.WindowManager;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private PowerManager.WakeLock screenLock;
    static boolean autoPip = true;

    // Bild-in-Bild: Sentinel wird klein unten rechts, hört aber weiter zu
    private PictureInPictureParams pipParams() {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder().setAspectRatio(new Rational(1, 1));
        if (Build.VERSION.SDK_INT >= 31) { b.setAutoEnterEnabled(autoPip); b.setSeamlessResizeEnabled(true); }
        return b.build();
    }
    public boolean enterPip() {
        if (Build.VERSION.SDK_INT < 26) return false;
        try { return enterPictureInPictureMode(pipParams()); } catch (Exception e) { return false; }
    }
    public void refreshPip() { if (Build.VERSION.SDK_INT >= 26) try { setPictureInPictureParams(pipParams()); } catch (Exception ignored) {} }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (autoPip && Build.VERSION.SDK_INT >= 26 && Build.VERSION.SDK_INT < 31) enterPip();
    }

    // ---------------- Absturzberichte: gehen per Server an Robsens Telegram ----------------
    private static final String ABSTURZ_URL = "https://sentinel-server.robsen-sentinel.workers.dev/v1/absturz";
    static boolean absturzSenden(Context c, String trace) {
        HttpURLConnection h = null;
        try {
            SharedPreferences sp = c.getSharedPreferences("sentinel_absturz", MODE_PRIVATE);
            String id = sp.getString("id", null);
            if (id == null) { id = UUID.randomUUID().toString(); sp.edit().putString("id", id).commit(); }
            JSONObject b = new JSONObject();
            String ver = "?";
            try { android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0); ver = pi.versionName + " (" + (Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode) + ")"; } catch (Throwable ignored) {}
            b.put("version", ver);
            b.put("geraet", Build.MANUFACTURER + " " + Build.MODEL + " / " + Build.DEVICE);
            b.put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
            b.put("trace", trace);
            h = (HttpURLConnection) new URL(ABSTURZ_URL).openConnection();
            h.setConnectTimeout(2000); h.setReadTimeout(2000); h.setDoOutput(true); h.setRequestMethod("POST");
            h.setRequestProperty("content-type", "application/json"); h.setRequestProperty("authorization", "Bearer " + id);
            try (OutputStream o = h.getOutputStream()) { o.write(b.toString().getBytes("UTF-8")); }
            return h.getResponseCode() == 200;
        } catch (Throwable e) { return false; } finally { if (h != null) h.disconnect(); }
    }
    private void absturzWaechter() {
        final Context app = getApplicationContext();
        final SharedPreferences sp = app.getSharedPreferences("sentinel_absturz", MODE_PRIVATE);
        final String alt = sp.getString("trace", null);
        if (alt != null) new Thread(() -> { if (absturzSenden(app, alt)) sp.edit().remove("trace").commit(); }).start();
        final Thread.UncaughtExceptionHandler vorher = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter(); e.printStackTrace(new PrintWriter(sw));
                final String tr = "Thread: " + t.getName() + "\n" + sw;
                sp.edit().putString("trace", tr).commit();
                Thread s = new Thread(() -> { if (absturzSenden(app, tr)) sp.edit().remove("trace").commit(); });
                s.start(); s.join(2500);
            } catch (Throwable ignored) {}
            if (vorher != null) vorher.uncaughtException(t, e); else System.exit(2);
        });
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        try { absturzWaechter(); } catch (Throwable ignored) {}
        registerPlugin(SentinelVoicePlugin.class);
        super.onCreate(savedInstanceState);
        try { keepOn(); } catch (Throwable ignored) {}
    }

    // Bildschirm bleibt an – Sentinel steht ja im Wohnzimmer.
    // Mehrfach abgesichert, weil manche Tablets (z. B. Lenovo) das einfache Flag nach einer Weile ignorieren.
    private void keepOn() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        getWindow().getDecorView().setKeepScreenOn(true);
        if (getBridge() != null && getBridge().getWebView() != null) getBridge().getWebView().setKeepScreenOn(true);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onResume() {
        super.onResume();
        try { keepOn(); } catch (Throwable ignored) {}
        refreshPip();
        try {
            if (screenLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                screenLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "sentinel:screen");
                screenLock.setReferenceCounted(false);
            }
            if (!screenLock.isHeld()) screenLock.acquire();
        } catch (Exception ignored) {}
    }

    @Override
    public void onPause() {
        super.onPause();
        if (Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode()) return; // klein, aber weiter wach
        try { if (screenLock != null && screenLock.isHeld()) screenLock.release(); } catch (Exception ignored) {}
    }
}
