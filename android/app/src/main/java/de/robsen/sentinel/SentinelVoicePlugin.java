package de.robsen.sentinel;

import android.Manifest;
import android.content.Intent;
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

import java.util.ArrayList;
import java.util.Locale;

/**
 * Sentinels Ohren und Stimme als echte Android-Funktionen.
 * - Ohren: Android-Spracherkennung, die sich nach jedem Satz selbst neu startet (kein Antippen nötig).
 * - Stimme: Android-Sprachausgabe. Während er spricht, hört er nicht zu.
 */
@CapacitorPlugin(
    name = "SentinelVoice",
    permissions = { @Permission(strings = { Manifest.permission.RECORD_AUDIO }, alias = "mic") }
)
public class SentinelVoicePlugin extends Plugin implements RecognitionListener {

    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private Intent recognizerIntent;
    private boolean wanted = false;      // soll zuhören
    private boolean listening = false;   // hört gerade
    private boolean speaking = false;    // spricht gerade
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

    @PluginMethod
    public void start(PluginCall call) {
        muteBeep = call.getBoolean("muteBeep", true);
        if (getPermissionState("mic") != PermissionState.GRANTED) {
            requestPermissionForAlias("mic", call, "micCallback");
            return;
        }
        wanted = true;
        main.post(this::listen);
        call.resolve();
    }

    @PermissionCallback
    private void micCallback(PluginCall call) {
        if (getPermissionState("mic") == PermissionState.GRANTED) {
            wanted = true;
            main.post(this::listen);
            call.resolve();
        } else {
            call.reject("Mikrofon nicht erlaubt");
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        wanted = false;
        main.post(() -> {
            if (recognizer != null) recognizer.cancel();
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
            // Erst nach 1,5 s Ruhe gilt ein Satz als fertig – kurze Atempausen schneiden nichts ab
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500);
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500);
            recognizerIntent = i;
        }
        return recognizerIntent;
    }

    private void listen() {
        if (!wanted || speaking || listening) return;
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(getContext());
                recognizer.setRecognitionListener(this);
            }
            muteBeepIfWanted();
            recognizer.startListening(intent());
            listening = true;
        } catch (Exception e) {
            listening = false;
            notifyListeners("error", new JSObject().put("code", -1).put("message", String.valueOf(e.getMessage())));
            recreate(1000);
        }
    }

    private void restart(long delayMs) {
        listening = false;
        main.postDelayed(this::listen, delayMs);
    }

    private void recreate(long delayMs) {
        listening = false;
        main.post(() -> {
            if (recognizer != null) { try { recognizer.destroy(); } catch (Exception ignored) {} }
            recognizer = null;
        });
        main.postDelayed(this::listen, delayMs);
    }

    @Override public void onReadyForSpeech(Bundle params) { notifyListeners("ready", new JSObject()); }
    @Override public void onBeginningOfSpeech() { notifyListeners("speechStart", new JSObject()); }
    @Override public void onRmsChanged(float rmsdB) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEndOfSpeech() {}
    @Override public void onEvent(int eventType, Bundle params) {}

    @Override
    public void onPartialResults(Bundle b) {
        String t = first(b);
        if (t != null && !t.isEmpty()) notifyListeners("partial", new JSObject().put("text", t));
    }

    @Override
    public void onResults(Bundle b) {
        String t = first(b);
        if (t != null && !t.isEmpty()) notifyListeners("final", new JSObject().put("text", t));
        restart(80);
    }

    @Override
    public void onError(int error) {
        listening = false;
        if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            notifyListeners("error", new JSObject().put("code", error));
        }
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                restart(60); break;                       // einfach weiter zuhören
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
            case SpeechRecognizer.ERROR_CLIENT:
                recreate(600); break;                     // Erkenner neu bauen
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                recreate(2500); break;                    // Internet-Schluckauf
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                wanted = false; unmuteBeep(); break;
            default:
                recreate(1000);
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
        main.post(() -> {
            speaking = true;
            if (recognizer != null) recognizer.cancel();   // beim Sprechen nicht zuhören
            listening = false;
            unmuteBeep();                                   // Ton an, sonst hört man ihn nicht
            tts.setSpeechRate(rate);
            tts.setPitch(pitch);
            Bundle p = new Bundle();
            p.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, p, id);
        });
        call.resolve();
    }

    @PluginMethod
    public void stopSpeaking(PluginCall call) {
        main.post(() -> { if (tts != null) tts.stop(); afterSpeak("stop"); });
        call.resolve();
    }

    private void afterSpeak(String id) {
        speaking = false;
        notifyListeners("ttsDone", new JSObject().put("id", id));
        main.postDelayed(this::listen, 250);              // danach direkt wieder zuhören
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

    // ---------------- Lebenszyklus ----------------

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        if (wanted) main.postDelayed(this::listen, 400);
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        unmuteBeep();
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        wanted = false;
        unmuteBeep();
        if (recognizer != null) { try { recognizer.destroy(); } catch (Exception ignored) {} }
        if (tts != null) tts.shutdown();
    }
}
