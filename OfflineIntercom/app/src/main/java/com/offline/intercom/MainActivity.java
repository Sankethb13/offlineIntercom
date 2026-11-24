package com.offline.intercom;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

@SuppressLint({"MissingPermission", "ClickableViewAccessibility", "UseSwitchCompatOrMaterialCode"})
public class MainActivity extends Activity implements WifiIntercom.Listener, BluetoothIntercom.Listener {

    private static final int REQ_PERMS = 1;

    private EditText nameEdit;
    private Spinner channelSpinner;
    private RadioGroup modeGroup;
    private View wifiPanel, btPanel;
    private Button wifiToggle, pttButton;
    private TextView peersText, statusText;
    private ListView btList;
    private Switch handsFree, speaker;

    private WifiIntercom wifi;
    private BluetoothIntercom bt;
    private BluetoothAdapter btAdapter;
    private final List<BluetoothDevice> bondedDevices = new ArrayList<>();
    private AudioManager audioManager;
    private SharedPreferences prefs;
    private boolean btConnected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = getSharedPreferences("intercom", MODE_PRIVATE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        nameEdit = findViewById(R.id.name);
        channelSpinner = findViewById(R.id.channel);
        modeGroup = findViewById(R.id.mode);
        wifiPanel = findViewById(R.id.wifiPanel);
        btPanel = findViewById(R.id.btPanel);
        wifiToggle = findViewById(R.id.wifiToggle);
        pttButton = findViewById(R.id.ptt);
        peersText = findViewById(R.id.peers);
        statusText = findViewById(R.id.status);
        btList = findViewById(R.id.btDevices);
        handsFree = findViewById(R.id.handsFree);
        speaker = findViewById(R.id.speaker);

        String defaultName = Build.MODEL;
        nameEdit.setText(prefs.getString("name", defaultName));

        String[] channels = new String[9];
        for (int i = 0; i < 9; i++) channels[i] = String.valueOf(i + 1);
        ArrayAdapter<String> chAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, channels);
        chAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        channelSpinner.setAdapter(chAdapter);
        channelSpinner.setSelection(prefs.getInt("channel", 1) - 1);
        channelSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putInt("channel", pos + 1).apply();
                if (wifi != null && wifi.isRunning()) {
                    wifi.setChannel(pos + 1);
                    onStatus("Switched to channel " + (pos + 1));
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) { }
        });

        wifi = new WifiIntercom(this, this);
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        btAdapter = bm != null ? bm.getAdapter() : null;
        if (btAdapter != null) bt = new BluetoothIntercom(btAdapter, this);

        modeGroup.setOnCheckedChangeListener((g, id) -> switchMode(id == R.id.modeBt));

        wifiToggle.setOnClickListener(v -> toggleWifi());

        findViewById(R.id.btHost).setOnClickListener(v -> {
            if (!checkBluetoothReady()) return;
            bt.host();
        });
        findViewById(R.id.btDisconnect).setOnClickListener(v -> { if (bt != null) bt.stop(); });
        findViewById(R.id.btSettings).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        btList.setOnItemClickListener((parent, view, position, id) -> {
            if (!checkBluetoothReady()) return;
            bt.join(bondedDevices.get(position));
        });

        pttButton.setOnTouchListener((v, ev) -> {
            if (handsFree.isChecked()) return false;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    setTalking(true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    setTalking(false);
                    return true;
            }
            return false;
        });

        handsFree.setOnCheckedChangeListener((b, on) -> {
            AudioEngine a = currentAudio();
            if (a != null) a.setMuteWhileTalking(!on);
            setTalking(on);
            pttButton.setText(on ? "HANDS-FREE ON" : "HOLD TO TALK");
        });

        speaker.setOnCheckedChangeListener((b, on) -> applySpeaker());

        updatePttEnabled();
        requestNeededPermissions();
    }

    // ---------------- modes ----------------

    private void switchMode(boolean bluetooth) {
        handsFree.setChecked(false);
        if (bluetooth) {
            if (wifi.isRunning()) toggleWifi();
            wifiPanel.setVisibility(View.GONE);
            btPanel.setVisibility(View.VISIBLE);
            refreshBondedDevices();
            onStatus(btAdapter == null ? "This phone has no Bluetooth" : "Bluetooth: one phone taps Host, the other taps it in the list");
        } else {
            if (bt != null) bt.stop();
            btPanel.setVisibility(View.GONE);
            wifiPanel.setVisibility(View.VISIBLE);
            onStatus("Join the same Wi-Fi (or one phone's hotspot) — internet not needed");
        }
        updatePttEnabled();
    }

    private void toggleWifi() {
        if (wifi.isRunning()) {
            handsFree.setChecked(false);
            wifi.stop();
            releaseAudioRouting();
            wifiToggle.setText("Start Wi-Fi intercom");
        } else {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) { requestNeededPermissions(); return; }
            saveName();
            try {
                wifi.start(myName(), channelSpinner.getSelectedItemPosition() + 1);
                setupAudioRouting();
                wifiToggle.setText("Stop Wi-Fi intercom");
            } catch (Exception e) {
                onStatus("Could not start Wi-Fi intercom: " + e.getMessage());
            }
        }
        updatePttEnabled();
    }

    private boolean checkBluetoothReady() {
        if (btAdapter == null) { toast("No Bluetooth on this phone"); return false; }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)
                || (Build.VERSION.SDK_INT >= 31 && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT))) {
            requestNeededPermissions();
            return false;
        }
        if (!btAdapter.isEnabled()) {
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return false;
        }
        saveName();
        return true;
    }

    private void refreshBondedDevices() {
        bondedDevices.clear();
        List<String> names = new ArrayList<>();
        if (btAdapter != null && (Build.VERSION.SDK_INT < 31 || hasPermission(Manifest.permission.BLUETOOTH_CONNECT))) {
            try {
                for (BluetoothDevice d : btAdapter.getBondedDevices()) {
                    bondedDevices.add(d);
                    names.add((d.getName() != null ? d.getName() : "Unknown") + "\n" + d.getAddress());
                }
            } catch (SecurityException ignored) { }
        }
        if (names.isEmpty()) names.add("No paired phones yet — tap \"Pair…\" and pair both phones first");
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names) {
            @Override public View getView(int pos, View convertView, android.view.ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, convertView, parent);
                t.setTextColor(0xFFFFFFFF);
                return t;
            }
            @Override public boolean isEnabled(int position) { return !bondedDevices.isEmpty(); }
        };
        btList.setAdapter(a);
    }

    // ---------------- audio ----------------

    private AudioEngine currentAudio() {
        if (modeGroup.getCheckedRadioButtonId() == R.id.modeBt) return bt != null ? bt.getAudio() : null;
        return wifi.isRunning() ? wifi.getAudio() : null;
    }

    private void setTalking(boolean on) {
        AudioEngine a = currentAudio();
        if (a == null) return;
        a.setMuteWhileTalking(!handsFree.isChecked());
        a.setTransmitting(on);
        pttButton.setSelected(on);
        if (!handsFree.isChecked()) pttButton.setText(on ? "TALKING…" : "HOLD TO TALK");
    }

    private void setupAudioRouting() {
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        applySpeaker();
    }

    @SuppressWarnings("deprecation")
    private void applySpeaker() {
        boolean on = speaker.isChecked();
        if (Build.VERSION.SDK_INT >= 31) {
            if (on) {
                for (AudioDeviceInfo d : audioManager.getAvailableCommunicationDevices()) {
                    if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        audioManager.setCommunicationDevice(d);
                        break;
                    }
                }
            } else {
                audioManager.clearCommunicationDevice();
            }
        } else {
            audioManager.setSpeakerphoneOn(on);
        }
    }

    @SuppressWarnings("deprecation")
    private void releaseAudioRouting() {
        if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice();
        else audioManager.setSpeakerphoneOn(false);
        audioManager.setMode(AudioManager.MODE_NORMAL);
    }

    private void updatePttEnabled() {
        boolean on = modeGroup.getCheckedRadioButtonId() == R.id.modeBt ? btConnected : wifi.isRunning();
        pttButton.setEnabled(on);
        handsFree.setEnabled(on);
        if (!on) {
            pttButton.setSelected(false);
            pttButton.setText("HOLD TO TALK");
        }
    }

    // ---------------- callbacks (background threads) ----------------

    @Override public void onPeersChanged(List<String> peerNames) {
        runOnUiThread(() -> {
            peersText.setText(peerNames.isEmpty() ? "Nobody yet…" : android.text.TextUtils.join("\n", peerNames));
            if (wifi.isRunning()) {
                statusText.setText(peerNames.isEmpty()
                        ? "Looking for others on channel " + (channelSpinner.getSelectedItemPosition() + 1) + "…"
                        : peerNames.size() + " connected · hold the button to talk");
            }
        });
    }

    @Override public void onTalker(String name) {
        runOnUiThread(() -> statusText.setText("🔊 " + name + " is talking"));
    }

    @Override public void onStatus(String text) {
        runOnUiThread(() -> statusText.setText(text));
    }

    @Override public void onConnected(String deviceName) {
        runOnUiThread(() -> {
            btConnected = true;
            setupAudioRouting();
            statusText.setText("Connected to " + deviceName + " · hold the button to talk");
            updatePttEnabled();
        });
    }

    @Override public void onDisconnected(String reason) {
        runOnUiThread(() -> {
            btConnected = false;
            handsFree.setChecked(false);
            releaseAudioRouting();
            statusText.setText(reason);
            updatePttEnabled();
        });
    }

    // ---------------- helpers ----------------

    private String myName() {
        String n = nameEdit.getText().toString().trim();
        return n.isEmpty() ? Build.MODEL : n;
    }

    private void saveName() {
        prefs.edit().putString("name", myName()).apply();
        wifi.setName(myName());
    }

    private boolean hasPermission(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNeededPermissions() {
        List<String> need = new ArrayList<>();
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31 && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) toast("Microphone permission is needed to talk");
        if (btPanel.getVisibility() == View.VISIBLE) refreshBondedDevices();
    }

    @Override protected void onResume() {
        super.onResume();
        if (btPanel.getVisibility() == View.VISIBLE) refreshBondedDevices();
    }

    @Override protected void onDestroy() {
        wifi.stop();
        if (bt != null) bt.stop();
        releaseAudioRouting();
        super.onDestroy();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
