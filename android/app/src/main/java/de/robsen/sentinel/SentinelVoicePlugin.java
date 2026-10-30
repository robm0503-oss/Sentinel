package de.robsen.sentinel;

import android.Manifest;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import android.content.ActivityNotFoundException;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.view.WindowManager;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Locale;

/**
 * Sentinels Ohren und Stimme als echte Android-Funktionen.
 * - Ohren: Android-Spracherkennung, die sich nach jedem Satz selbst neu startet (kein Antippen nötig).
 * - Stimme: Android-Sprachausgabe. Während er spricht, hört er nicht zu.
 */
@CapacitorPlugin(
    name = "SentinelVoice",
    permissions = {
        @Permission(strings = { Manifest.permission.RECORD_AUDIO }, alias = "mic"),
        @Permission(strings = { Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR }, alias = "calendar")
    }
)
public class SentinelVoicePlugin extends Plugin implements RecognitionListener {

    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private Intent recognizerIntent;
    private boolean wanted = false;      // soll zuhören
    private boolean listening = false;   // hört gerade
    private boolean speaking = false;    // spricht gerade
    private boolean paused = false;      // App im Hintergrund (z. B. YouTube offen) -> nicht zuhören, nichts stummschalten
    private boolean muteBeep = true;
    private boolean beepMuted = false;
    private AudioManager audio;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private float rate = 1.0f, pitch = 1.0f;
    private String voiceName = null;

    @Override
    public void load() {
        audio = (AudioManager) getContext().getSystemService(android.content.Context.AUDIO_SERVICE);
        tts = new TextToSpeech(getContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.GERMANY);
                ttsReady = true;
                applyVoice();
            }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { notifyListeners("ttsStart", new JSObject().put("id", id)); }
            @Override public void onDone(String id) { main.post(() -> afterSpeak(id)); }
            @Override public void onError(String id) { main.post(() -> afterSpeak(id)); }
        });
    }

    // ---------------- Ohren ----------------

    private long busyBackoff = 1000;
    private String lastPartial = "";
    private final Runnable listenRunnable = this::listen;

    @PluginMethod
    public void start(PluginCall call) {
        muteBeep = call.getBoolean("muteBeep", true);
        if (getPermissionState("mic") != PermissionState.GRANTED) {
            requestPermissionForAlias("mic", call, "micCallback");
            return;
        }
        wanted = true;
        if (!listening && !speaking) restart(100);
        call.resolve();
    }

    @PermissionCallback
    private void micCallback(PluginCall call) {
        if (getPermissionState("mic") == PermissionState.GRANTED) {
            wanted = true;
            restart(100);
            call.resolve();
        } else {
            call.reject("Mikrofon nicht erlaubt");
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        wanted = false;
        main.post(() -> {
            main.removeCallbacks(listenRunnable);
            destroyRecognizer();
            listening = false;
            unmuteBeep();
        });
        call.resolve();
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        r.put("wanted", wanted);
        r.put("listening", listening);
        r.put("speaking", speaking);
        r.put("available", SpeechRecognizer.isRecognitionAvailable(getContext()));
        call.resolve(r);
    }

    private Intent intent() {
        if (recognizerIntent == null) {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE");
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getContext().getPackageName());
            recognizerIntent = i;
        }
        return recognizerIntent;
    }

    // Jede Zuhör-Runde bekommt einen frischen Erkenner. Das vermeidet "Fehler 8 – Erkenner beschäftigt",
    // den manche Tablets werfen, wenn man denselben Erkenner sofort wieder startet.
    private void listen() {
        if (!wanted || speaking || listening || paused) return;
        try {
            destroyRecognizer();
            recognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
            recognizer.setRecognitionListener(this);
            muteBeepIfWanted();
            recognizer.startListening(intent());
            listening = true;
        } catch (Exception e) {
            listening = false;
            notifyListeners("error", new JSObject().put("code", -1).put("message", String.valueOf(e.getMessage())));
            restart(1500);
        }
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
            try { recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
    }

    private void restart(long delayMs) {
        listening = false;
        main.removeCallbacks(listenRunnable);
        main.postDelayed(listenRunnable, delayMs);
    }

    @Override public void onReadyForSpeech(Bundle params) { busyBackoff = 1000; lastPartial = ""; notifyListeners("ready", new JSObject()); }
    @Override public void onBeginningOfSpeech() { notifyListeners("speechStart", new JSObject()); }
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {}
    @Override public void onEvent(int eventType, Bundle params) {}

    @Override
    public void onPartialResults(Bundle b) {
        String t = first(b);
        if (t != null && !t.isEmpty()) { lastPartial = t; notifyListeners("partial", new JSObject().put("text", t)); }
    }

    @Override
    public void onResults(Bundle b) {
        String t = first(b);
        // Manche Tablets liefern am Ende ein leeres Ergebnis, obwohl vorher Text kam -> den nehmen
        if ((t == null || t.isEmpty()) && !lastPartial.isEmpty()) t = lastPartial;
        lastPartial = "";
        notifyListeners("final", new JSObject().put("text", t == null ? "" : t));
        listening = false;
        destroyRecognizer();
        restart(250);
    }

    @Override
    public void onError(int error) {
        listening = false;
        // "Nichts verstanden", aber vorher kam schon Text -> den Text trotzdem weitergeben
        if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && !lastPartial.isEmpty()) {
            notifyListeners("final", new JSObject().put("text", lastPartial));
            lastPartial = "";
            destroyRecognizer();
            restart(250);
            return;
        }
        notifyListeners("error", new JSObject().put("code", error));
        destroyRecognizer();
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                restart(250); break;                       // einfach weiter zuhören
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:   // 8: kurz warten, jedes Mal etwas länger
                restart(busyBackoff);
                busyBackoff = Math.min(busyBackoff * 2, 8000);
                break;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                restart(2500); break;                      // Internet-Schluckauf
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                wanted = false; unmuteBeep(); break;
            default:
                restart(1200);
        }
    }

    private String first(Bundle b) {
        if (b == null) return null;
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (r != null && !r.isEmpty()) ? r.get(0) : null;
    }

    // Der Start-Piepton der Spracherkennung wird stummgeschaltet (sonst piept es bei jedem Satz)
    private void muteBeepIfWanted() {
        if (!muteBeep || beepMuted || audio == null || Build.VERSION.SDK_INT < 23) return;
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
            audio.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0);
            audio.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0);
            beepMuted = true;
        } catch (Exception ignored) {}
    }

    private void unmuteBeep() {
        if (!beepMuted || audio == null || Build.VERSION.SDK_INT < 23) return;
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            audio.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0);
            audio.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0);
        } catch (Exception ignored) {}
        beepMuted = false;
    }

    // ---------------- Stimme ----------------

    @PluginMethod
    public void speak(PluginCall call) {
        String text = call.getString("text", "");
        String id = call.getString("id", String.valueOf(System.currentTimeMillis()));
        if (call.getFloat("rate") != null) rate = call.getFloat("rate");
        if (call.getFloat("pitch") != null) pitch = call.getFloat("pitch");
        if (!ttsReady || text.isEmpty()) {
            notifyListeners("ttsDone", new JSObject().put("id", id));
            call.resolve();
            return;
        }
        final float vol = Math.max(0.05f, Math.min(1f, call.getFloat("volume", 1f)));
        final long guardMs = Math.max(8000, text.length() * 150L);
        main.post(() -> {
            main.removeCallbacks(speakGuard);
            main.postDelayed(speakGuard, guardMs);   // falls Android nie "fertig" meldet
            speaking = true;
            main.removeCallbacks(listenRunnable);
            destroyRecognizer();                            // beim Sprechen nicht zuhören
            listening = false;
            unmuteBeep();                                   // Ton an, sonst hört man ihn nicht
            tts.setSpeechRate(rate);
            tts.setPitch(pitch);
            Bundle p = new Bundle();
            p.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
            p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, vol);   // Nachtmodus: leiser
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, p, id);
        });
        call.resolve();
    }

    @PluginMethod
    public void stopSpeaking(PluginCall call) {
        main.post(() -> { if (tts != null) tts.stop(); afterSpeak("stop"); });
        call.resolve();
    }

    private final Runnable speakGuard = () -> {
        if (speaking) { try { tts.stop(); } catch (Exception ignored) {} afterSpeak("guard"); }
    };

    private void afterSpeak(String id) {
        main.removeCallbacks(speakGuard);
        speaking = false;
        notifyListeners("ttsDone", new JSObject().put("id", id));
        if (wanted) restart(350);                         // danach direkt wieder zuhören
    }

    @PluginMethod
    public void voices(PluginCall call) {
        JSArray arr = new JSArray();
        if (ttsReady && tts.getVoices() != null) {
            for (Voice v : tts.getVoices()) {
                if (v.getLocale() != null && "de".equals(v.getLocale().getLanguage())) {
                    JSObject o = new JSObject();
                    o.put("name", v.getName());
                    o.put("quality", v.getQuality());
                    o.put("network", v.isNetworkConnectionRequired());
                    arr.put(o);
                }
            }
        }
        JSObject res = new JSObject();
        try { res.put("voices", arr); } catch (Exception ignored) {}
        call.resolve(res);
    }

    @PluginMethod
    public void setVoice(PluginCall call) {
        voiceName = call.getString("name");
        main.post(this::applyVoice);
        call.resolve();
    }

    private void applyVoice() {
        if (!ttsReady || voiceName == null || tts.getVoices() == null) return;
        for (Voice v : tts.getVoices()) {
            if (v.getName().equals(voiceName)) { tts.setVoice(v); return; }
        }
    }

    // ---------------- Tablet bedienen (Stufe 1) ----------------

    @PluginMethod
    public void listApps(PluginCall call) {
        PackageManager pm = getContext().getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN, null);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        JSArray arr = new JSArray();
        for (ResolveInfo r : list) {
            JSObject o = new JSObject();
            o.put("name", String.valueOf(r.loadLabel(pm)));
            o.put("package", r.activityInfo.packageName);
            arr.put(o);
        }
        JSObject res = new JSObject();
        try { res.put("apps", arr); } catch (Exception ignored) {}
        call.resolve(res);
    }

    @PluginMethod
    public void launchApp(PluginCall call) {
        String pkg = call.getString("package", "");
        Intent i = getContext().getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) { call.reject("App nicht gefunden"); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { getContext().startActivity(i); call.resolve(); }
        catch (Exception e) { call.reject("App ließ sich nicht öffnen"); }
    }

    @PluginMethod
    public void openUrl(PluginCall call) {
        String url = call.getString("url", "");
        String pkg = call.getString("package");
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (pkg != null && !pkg.isEmpty()) i.setPackage(pkg);
        try { getContext().startActivity(i); call.resolve(); }
        catch (ActivityNotFoundException e) {
            if (pkg != null) {   // App nicht da -> ohne feste App nochmal (z. B. im Browser)
                i.setPackage(null);
                try { getContext().startActivity(i); call.resolve(); return; } catch (Exception ignored) {}
            }
            call.reject("Keine App für diesen Link gefunden");
        }
    }

    @PluginMethod
    public void setAlarm(PluginCall call) {
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM);
        i.putExtra(AlarmClock.EXTRA_HOUR, call.getInt("hour", 7));
        i.putExtra(AlarmClock.EXTRA_MINUTES, call.getInt("minute", 0));
        String label = call.getString("label");
        if (label != null) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { getContext().startActivity(i); call.resolve(); }
        catch (Exception e) { call.reject("Keine Wecker-App gefunden"); }
    }

    @PluginMethod
    public void addEvent(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI);
        i.putExtra(CalendarContract.Events.TITLE, call.getString("title", "Termin"));
        Double begin = call.getDouble("begin");
        Double end = call.getDouble("end");
        if (begin != null) i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin.longValue());
        if (end != null) i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.longValue());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { getContext().startActivity(i); call.resolve(); }
        catch (Exception e) { call.reject("Keine Kalender-App gefunden"); }
    }

    // Erinnerung direkt in den Google-Kalender schreiben (ohne Nachfrage). Der Kalender synchronisiert
    // sie aufs Handy – so klingelt sie dort auch, wenn das Tablet aus ist.
    @PluginMethod
    public void reminderEvent(PluginCall call) {
        if (getPermissionState("calendar") != PermissionState.GRANTED) { requestPermissionForAlias("calendar", call, "calendarCallback"); return; }
        doReminderEvent(call);
    }

    @PermissionCallback
    private void calendarCallback(PluginCall call) {
        if (getPermissionState("calendar") == PermissionState.GRANTED) doReminderEvent(call);
        else call.reject("Kalender nicht erlaubt");
    }

    private void doReminderEvent(PluginCall call) {
        try {
            android.content.ContentResolver cr = getContext().getContentResolver();
            long calId = -1; String account = "";
            String[] proj = { CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL };
            try (android.database.Cursor c = cr.query(CalendarContract.Calendars.CONTENT_URI, proj, CalendarContract.Calendars.VISIBLE + "=1", null, null)) {
                int best = -1;
                while (c != null && c.moveToNext()) {
                    if (c.getInt(4) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue;
                    int score = ("com.google".equals(c.getString(2)) ? 2 : 0) + (c.getInt(3) == 1 ? 1 : 0);
                    if (score > best) { best = score; calId = c.getLong(0); account = c.getString(1); }
                }
            }
            if (calId < 0) { call.reject("Kein Kalender auf dem Tablet gefunden"); return; }
            long begin = call.getDouble("begin", (double) System.currentTimeMillis()).longValue();
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(CalendarContract.Events.CALENDAR_ID, calId);
            v.put(CalendarContract.Events.TITLE, call.getString("title", "Erinnerung"));
            v.put(CalendarContract.Events.DESCRIPTION, "Von Sentinel");
            v.put(CalendarContract.Events.DTSTART, begin);
            v.put(CalendarContract.Events.DTEND, begin + 15 * 60000L);
            v.put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().getID());
            v.put(CalendarContract.Events.HAS_ALARM, 1);
            Uri u = cr.insert(CalendarContract.Events.CONTENT_URI, v);
            if (u == null) { call.reject("Kalender hat den Eintrag abgelehnt"); return; }
            long id = Long.parseLong(u.getLastPathSegment());
            android.content.ContentValues r = new android.content.ContentValues();
            r.put(CalendarContract.Reminders.EVENT_ID, id);
            r.put(CalendarContract.Reminders.MINUTES, 0);
            r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
            cr.insert(CalendarContract.Reminders.CONTENT_URI, r);
            JSObject o = new JSObject(); o.put("id", id); o.put("account", account);
            call.resolve(o);
        } catch (Exception e) { call.reject("Kalender-Fehler: " + e.getMessage()); }
    }

    @PluginMethod
    public void deleteEvent(PluginCall call) {
        try {
            long id = call.getDouble("id", -1d).longValue();
            if (id > 0 && getPermissionState("calendar") == PermissionState.GRANTED)
                getContext().getContentResolver().delete(android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null);
        } catch (Exception ignored) {}
        call.resolve();
    }

    @PluginMethod
    public void setVolume(PluginCall call) {
        int pct = Math.max(0, Math.min(100, call.getInt("percent", 50)));
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * pct / 100f), 0);
        call.resolve();
    }

    @PluginMethod
    public void setBrightness(PluginCall call) {
        final int pc = call.getInt("percent", 60);
        // -1 = wieder automatisch / Systemhelligkeit
        final float v = pc < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : Math.max(0.02f, Math.min(1f, pc / 100f));
        getActivity().runOnUiThread(() -> {
            WindowManager.LayoutParams lp = getActivity().getWindow().getAttributes();
            lp.screenBrightness = v;
            getActivity().getWindow().setAttributes(lp);
        });
        call.resolve();
    }

    // Akkustand und ob gerade geladen wird
    @PluginMethod
    public void battery(PluginCall call) {
        Intent b = getContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        JSObject r = new JSObject();
        if (b == null) { r.put("level", -1); r.put("charging", false); call.resolve(r); return; }
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1), plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        r.put("level", scale > 0 ? Math.round(level * 100f / scale) : level);
        r.put("charging", plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
        r.put("full", status == BatteryManager.BATTERY_STATUS_FULL);
        call.resolve(r);
    }

    @PluginMethod
    public void bringToFront(PluginCall call) {
        Intent i = getContext().getPackageManager().getLaunchIntentForPackage(getContext().getPackageName());
        if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT); getContext().startActivity(i); }
        call.resolve();
    }

    // ---------------- WLAN-Scanner ----------------

    private static final int[] SCAN_PORTS = { 80, 443, 5555, 8008, 9999, 1400, 8080, 554 };
    private static final String[] MDNS_TYPES = { "_googlecast._tcp.", "_hue._tcp.", "_hap._tcp.", "_amzn-wplay._tcp.",
        "_spotify-connect._tcp.", "_airplay._tcp.", "_shelly._tcp.", "_http._tcp.", "_printer._tcp.", "_sonos._tcp." };

    private String localIp() {
        try {
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e.hasMoreElements();) {
                NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements();) {
                    InetAddress ad = a.nextElement();
                    if (ad instanceof Inet4Address && ad.isSiteLocalAddress()) return ad.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @PluginMethod
    public void scanNetwork(PluginCall call) {
        new Thread(() -> {
            String myIp = localIp();
            if (myIp == null) { call.reject("Kein WLAN gefunden"); return; }
            String prefix = myIp.substring(0, myIp.lastIndexOf('.') + 1);
            Map<String, List<Integer>> open = new ConcurrentHashMap<>();
            Map<String, List<String>> mdns = new ConcurrentHashMap<>();

            // 1) Geräte-Namen per Bonjour/mDNS einsammeln (läuft parallel zum Port-Test)
            Thread mdnsThread = new Thread(() -> discoverMdns(mdns, 6000));
            mdnsThread.start();

            // 2) Alle Adressen im Netz auf typische Geräte-Ports prüfen
            ExecutorService ex = Executors.newFixedThreadPool(64);
            List<Future<?>> jobs = new ArrayList<>();
            for (int h = 1; h < 255; h++) {
                final String ip = prefix + h;
                if (ip.equals(myIp)) continue;
                for (int port : SCAN_PORTS) {
                    final int pp = port;
                    jobs.add(ex.submit(() -> {
                        try (Socket sk = new Socket()) {
                            sk.connect(new InetSocketAddress(ip, pp), 400);
                            open.computeIfAbsent(ip, k -> Collections.synchronizedList(new ArrayList<>())).add(pp);
                        } catch (Exception ignored) {}
                    }));
                }
            }
            for (Future<?> f : jobs) { try { f.get(); } catch (Exception ignored) {} }

            // 3) Namen beim Router nachfragen (falls bekannt)
            Map<String, String> host = new ConcurrentHashMap<>();
            List<Future<?>> nameJobs = new ArrayList<>();
            List<String> ips = new ArrayList<>(open.keySet());
            for (String ip : mdns.keySet()) if (!ips.contains(ip)) ips.add(ip);
            for (String ip : ips) {
                nameJobs.add(ex.submit(() -> {
                    try { String n = InetAddress.getByName(ip).getCanonicalHostName(); if (n != null && !n.equals(ip)) host.put(ip, n); } catch (Exception ignored) {}
                }));
            }
            for (Future<?> f : nameJobs) { try { f.get(1500, TimeUnit.MILLISECONDS); } catch (Exception ignored) {} }
            ex.shutdownNow();
            try { mdnsThread.join(7000); } catch (Exception ignored) {}

            JSArray arr = new JSArray();
            for (String ip : ips) {
                JSObject o = new JSObject();
                o.put("ip", ip);
                if (host.containsKey(ip)) o.put("name", host.get(ip));
                JSArray ports = new JSArray();
                List<Integer> pl = open.get(ip);
                if (pl != null) for (Integer pt : pl) ports.put(pt);
                JSArray svc = new JSArray();
                List<String> sl = mdns.get(ip);
                if (sl != null) for (String sv : sl) svc.put(sv);
                try { o.put("ports", ports); o.put("dienste", svc); } catch (Exception ignored) {}
                arr.put(o);
            }
            JSObject res = new JSObject();
            res.put("eigeneIp", myIp);
            try { res.put("geraete", arr); } catch (Exception ignored) {}
            call.resolve(res);
        }).start();
    }

    private void discoverMdns(Map<String, List<String>> out, long ms) {
        WifiManager wifi = (WifiManager) getContext().getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = null;
        try { lock = wifi.createMulticastLock("sentinel-scan"); lock.setReferenceCounted(false); lock.acquire(); } catch (Exception ignored) {}
        NsdManager nsd = (NsdManager) getContext().getSystemService(android.content.Context.NSD_SERVICE);
        List<NsdServiceInfo> found = Collections.synchronizedList(new ArrayList<>());
        List<NsdManager.DiscoveryListener> listeners = new ArrayList<>();
        for (String type : MDNS_TYPES) {
            NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
                @Override public void onStartDiscoveryFailed(String t, int c) {}
                @Override public void onStopDiscoveryFailed(String t, int c) {}
                @Override public void onDiscoveryStarted(String t) {}
                @Override public void onDiscoveryStopped(String t) {}
                @Override public void onServiceFound(NsdServiceInfo si) { found.add(si); }
                @Override public void onServiceLost(NsdServiceInfo si) {}
            };
            try { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l); listeners.add(l); } catch (Exception ignored) {}
        }
        try { Thread.sleep(ms / 2); } catch (Exception ignored) {}
        for (NsdManager.DiscoveryListener l : listeners) { try { nsd.stopServiceDiscovery(l); } catch (Exception ignored) {} }
        // Gefundene Dienste nacheinander auflösen (Android kann nur einen gleichzeitig)
        long until = System.currentTimeMillis() + ms / 2;
        List<NsdServiceInfo> copy = new ArrayList<>(found);
        for (NsdServiceInfo si : copy) {
            if (System.currentTimeMillis() > until) break;
            final CountDownLatch latch = new CountDownLatch(1);
            try {
                nsd.resolveService(si, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo s, int c) { latch.countDown(); }
                    @Override public void onServiceResolved(NsdServiceInfo s) {
                        if (s.getHost() != null) {
                            String ip = s.getHost().getHostAddress();
                            out.computeIfAbsent(ip, k -> Collections.synchronizedList(new ArrayList<>()))
                               .add(s.getServiceName() + " (" + s.getServiceType().replace(".", "").replace("_tcp", "").replace("_", "") + ")");
                        }
                        latch.countDown();
                    }
                });
                latch.await(1200, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {}
        }
        try { if (lock != null) lock.release(); } catch (Exception ignored) {}
    }

    // ---------------- Lebenszyklus ----------------

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        paused = false;
        if (wanted) restart(500);
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        paused = true;
        main.removeCallbacks(listenRunnable);
        destroyRecognizer();
        listening = false;
        unmuteBeep();
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        wanted = false;
        unmuteBeep();
        destroyRecognizer();
        if (tts != null) tts.shutdown();
    }
}
