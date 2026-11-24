package com.offline.intercom;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * One-to-one intercom over classic Bluetooth (RFCOMM). One phone taps "Host",
 * the other picks it from the list of paired devices. No Wi-Fi or internet used.
 */
@SuppressLint("MissingPermission")
public class BluetoothIntercom {

    public interface Listener {
        void onConnected(String deviceName);
        void onDisconnected(String reason);
        void onTalker(String name);
        void onStatus(String text);
    }

    public static final UUID SERVICE_UUID = UUID.fromString("6f1c2a5e-9b0d-4c8e-a3f7-1d2e3b4c5a60");
    public static final int SAMPLE_RATE = 8000; // keeps the Bluetooth link light and stable

    private final BluetoothAdapter adapter;
    private final Listener listener;

    private volatile boolean active;
    private BluetoothServerSocket serverSocket;
    private BluetoothSocket socket;
    private DataOutputStream out;
    private AudioEngine audio;
    private String peerName = "Partner";

    public BluetoothIntercom(BluetoothAdapter adapter, Listener listener) {
        this.adapter = adapter;
        this.listener = listener;
    }

    public AudioEngine getAudio() { return audio; }

    public boolean isActive() { return active; }

    /** Wait for the other phone to connect. */
    public void host() {
        stop();
        active = true;
        listener.onStatus("Hosting… on the other phone choose this device");
        new Thread(() -> {
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord("OfflineIntercom", SERVICE_UUID);
                BluetoothSocket s = serverSocket.accept();
                try { serverSocket.close(); } catch (IOException ignored) { }
                serverSocket = null;
                onSocket(s);
            } catch (IOException e) {
                if (active) fail("Hosting stopped: " + e.getMessage());
            }
        }, "bt-host").start();
    }

    /** Connect to a paired phone that is hosting. */
    public void join(BluetoothDevice device) {
        stop();
        active = true;
        listener.onStatus("Connecting to " + device.getName() + "…");
        new Thread(() -> {
            try {
                adapter.cancelDiscovery();
                BluetoothSocket s = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
                s.connect();
                onSocket(s);
            } catch (IOException e) {
                if (active) fail("Could not connect. Make sure the other phone tapped Host. (" + e.getMessage() + ")");
            }
        }, "bt-join").start();
    }

    private void onSocket(BluetoothSocket s) throws IOException {
        socket = s;
        BluetoothDevice d = s.getRemoteDevice();
        peerName = d.getName() != null ? d.getName() : d.getAddress();
        out = new DataOutputStream(s.getOutputStream());
        DataInputStream in = new DataInputStream(s.getInputStream());

        audio = new AudioEngine(SAMPLE_RATE, this::sendAudio);
        audio.start();
        listener.onConnected(peerName);

        byte[] buf = new byte[4096];
        long lastAudio = 0;
        try {
            while (active) {
                int len = in.readUnsignedShort();
                if (len <= 0 || len > buf.length) throw new IOException("bad frame");
                in.readFully(buf, 0, len);
                long now = System.currentTimeMillis();
                if (now - lastAudio > 500) listener.onTalker(peerName);
                lastAudio = now;
                audio.push("bt", buf, 0, len);
            }
        } catch (IOException e) {
            if (active) fail("Connection lost");
        }
    }

    private void sendAudio(byte[] pcm, int len) {
        DataOutputStream o = out;
        if (o == null) return;
        try {
            synchronized (this) {
                o.writeShort(len);
                o.write(pcm, 0, len);
                o.flush();
            }
        } catch (IOException e) {
            if (active) fail("Connection lost");
        }
    }

    private void fail(String reason) {
        cleanup();
        listener.onDisconnected(reason);
    }

    public void stop() {
        boolean wasActive = active;
        cleanup();
        if (wasActive) listener.onDisconnected("Disconnected");
    }

    private void cleanup() {
        active = false;
        if (audio != null) { audio.stop(); audio = null; }
        out = null;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        serverSocket = null;
        socket = null;
    }
}
