package de.robsen.sentinel;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Gerätesteuerung (nur in der Test-Ausführung „geraet“ im Manifest eingetragen, nie im Play Store).
 * Liest den Bildschirm als Liste von Elementen und führt Tippen/Schreiben/Wischen aus – nur auf Befehl aus Sentinel.
 */
public class SentinelGeraetesteuerung extends AccessibilityService {
    static volatile SentinelGeraetesteuerung instanz;
    private final List<AccessibilityNodeInfo> elemente = new ArrayList<>();

    @Override protected void onServiceConnected() { instanz = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
    @Override public boolean onUnbind(android.content.Intent intent) { instanz = null; return super.onUnbind(intent); }
    @Override public void onDestroy() { instanz = null; super.onDestroy(); }

    /** Bildschirm lesen: sichtbare Texte und bedienbare Elemente mit Nummern. */
    synchronized JSONObject lesen() throws Exception {
        elemente.clear();
        JSONObject out = new JSONObject();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        JSONArray liste = new JSONArray();
        if (root != null) {
            out.put("app", String.valueOf(root.getPackageName()));
            sammeln(root, liste, 0);
        }
        out.put("elemente", liste);
        return out;
    }

    private void sammeln(AccessibilityNodeInfo n, JSONArray liste, int tiefe) throws Exception {
        if (n == null || tiefe > 40 || liste.length() >= 150) return;
        if (!n.isVisibleToUser()) return;
        CharSequence t = n.getText(), d = n.getContentDescription();
        String text = t != null ? t.toString() : (d != null ? d.toString() : "");
        boolean bedienbar = n.isClickable() || n.isEditable() || n.isCheckable() || n.isScrollable() || n.isLongClickable();
        if (!text.trim().isEmpty() || bedienbar) {
            int id = elemente.size();
            elemente.add(n);
            JSONObject e = new JSONObject();
            e.put("nr", id);
            if (!text.trim().isEmpty()) e.put("text", text.length() > 120 ? text.substring(0, 120) + "…" : text);
            String art = n.isEditable() ? "eingabefeld" : n.isCheckable() ? (n.isChecked() ? "schalter_an" : "schalter_aus") : n.isClickable() ? "knopf" : n.isScrollable() ? "liste" : "text";
            e.put("art", art);
            if (n.isPassword()) e.put("passwort", true);
            Rect r = new Rect(); n.getBoundsInScreen(r);
            e.put("x", r.centerX()); e.put("y", r.centerY());
            liste.put(e);
        }
        for (int i = 0; i < n.getChildCount(); i++) sammeln(n.getChild(i), liste, tiefe + 1);
    }

    private AccessibilityNodeInfo element(int nr) {
        if (nr < 0 || nr >= elemente.size()) throw new IllegalArgumentException("Element " + nr + " gibt es nicht – erst den Bildschirm neu lesen.");
        return elemente.get(nr);
    }

    synchronized boolean tippen(int nr, boolean lang) {
        AccessibilityNodeInfo n = element(nr);
        // nach oben gehen, bis ein klickbares Element kommt
        AccessibilityNodeInfo k = n;
        while (k != null && !(lang ? k.isLongClickable() : k.isClickable())) k = k.getParent();
        if (k != null && k.performAction(lang ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Rect r = new Rect(); n.getBoundsInScreen(r);
        return geste(r.centerX(), r.centerY(), r.centerX(), r.centerY(), lang ? 700 : 60);
    }

    synchronized boolean schreiben(int nr, String text) {
        AccessibilityNodeInfo n = element(nr);
        if (n.isPassword()) throw new IllegalArgumentException("Passwortfelder fülle ich nicht aus – das machst du bitte selbst.");
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    boolean wischen(String richtung) {
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        int w = m.widthPixels, h = m.heightPixels, cx = w / 2, cy = h / 2;
        switch (richtung) {
            case "runter": return geste(cx, (int) (h * 0.75), cx, (int) (h * 0.25), 350);   // Inhalt nach unten scrollen
            case "hoch": return geste(cx, (int) (h * 0.25), cx, (int) (h * 0.75), 350);
            case "links": return geste((int) (w * 0.85), cy, (int) (w * 0.15), cy, 300);
            case "rechts": return geste((int) (w * 0.15), cy, (int) (w * 0.85), cy, 300);
            default: throw new IllegalArgumentException("Richtung: hoch, runter, links oder rechts");
        }
    }

    boolean geste(int x1, int y1, int x2, int y2, long dauer) {
        if (Build.VERSION.SDK_INT < 24) return false;
        Path p = new Path(); p.moveTo(x1, y1); p.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(1, dauer))).build();
        return dispatchGesture(g, null, null);
    }

    boolean global(String was) {
        switch (was) {
            case "zurueck": return performGlobalAction(GLOBAL_ACTION_BACK);
            case "home": return performGlobalAction(GLOBAL_ACTION_HOME);
            case "apps": return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case "benachrichtigungen": return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            default: throw new IllegalArgumentException("unbekannt: " + was);
        }
    }
}
