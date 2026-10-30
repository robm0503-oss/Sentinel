package de.robsen.sentinel;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.WindowManager;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    private PowerManager.WakeLock screenLock;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SentinelVoicePlugin.class);
        super.onCreate(savedInstanceState);
        keepOn();
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
        keepOn();
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
        try { if (screenLock != null && screenLock.isHeld()) screenLock.release(); } catch (Exception ignored) {}
    }
}
