package de.robsen.sentinel;

import android.os.Bundle;
import android.view.WindowManager;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SentinelVoicePlugin.class);
        super.onCreate(savedInstanceState);
        // Bildschirm bleibt an – Sentinel steht ja im Wohnzimmer
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
