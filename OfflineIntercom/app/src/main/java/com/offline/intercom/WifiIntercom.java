package com.offline.intercom;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Intercom over a local Wi-Fi network (router or phone hotspot) – no internet required.
 * Peers find each other with UDP broadcast "hello" packets, then audio is sent by UDP
 * unicast to every peer on the same channel. Supports many people at once.
 */
public class WifiIntercom {

    public interface Listener {
        void onPeersChanged(List<String> peerNames);
        void onTalker(String name);
        void onStatus(String text);
    }

    public static final int PORT = 50505;
    public static final int SAMPLE_RATE = 16000;

    private static final byte[] MAGIC = {'I', 'C'};
    private static final byte TYPE_HELLO = 1;
    private static final byte TYPE_AUDIO = 2;
    private static final byte TYPE_BYE = 3;
    private static final int HEADER = 10; // magic(2) type(1) channel(1) id(4) seq(2)
    private static final long PEER_TIMEOUT_MS = 7000;

    private static class Peer {
        String name;
        InetAddress addr;
        long lastSeen;
        long lastAudio;
    }

    private final Context ctx;
    private final Listener listener;
    private final int myId = new Random().nextInt() | 1;
    private final Map<Integer, Peer> peers = new LinkedHashMap<>();

    private volatile String myName;
    private volatile int channel;
    private volatile boolean running;
    private DatagramSocket socket;
    private AudioEngine audio;
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private Thread rxThread, helloThread;
    private short seq;

    public WifiIntercom(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public AudioEngine getAudio() { return audio; }

    public boolean isRunning() { return running; }

    public void setName(String name) { myName = name; }

    public void setChannel(int ch) {
        channel = ch;
        synchronized (peers) { peers.clear(); }
        publishPeers();
    }

    public void start(String name, int ch) throws Exception {
        if (running) return;
        myName = name;
        channel = ch;

        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            multicastLock = wm.createMulticastLock("intercom");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "intercom");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }

        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.setBroadcast(true);
        socket.bind(new InetSocketAddress(PORT));

        audio = new AudioEngine(SAMPLE_RATE, this::sendAudio);
        running = true;
        audio.start();

        rxThread = new Thread(this::receiveLoop, "wifi-rx");
        helloThread = new Thread(this::helloLoop, "wifi-hello");
        rxThread.start();
        helloThread.start();
        listener.onStatus("Wi-Fi intercom on · channel " + ch + " · looking for others…");
    }

    public void stop() {
        if (!running) return;
        running = false;
        try { sendBroadcast(buildHeader(TYPE_BYE, 0), HEADER); } catch (Exception ignored) { }
        if (audio != null) audio.stop();
        if (socket != null) socket.close();
        if (helloThread != null) helloThread.interrupt();
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        synchronized (peers) { peers.clear(); }
        publishPeers();
        listener.onStatus("Wi-Fi intercom stopped");
    }

    private byte[] buildHeader(byte type, int extra) {
        byte[] p = new byte[HEADER + extra];
        p[0] = MAGIC[0];
        p[1] = MAGIC[1];
        p[2] = type;
        p[3] = (byte) channel;
        p[4] = (byte) (myId >> 24);
        p[5] = (byte) (myId >> 16);
        p[6] = (byte) (myId >> 8);
        p[7] = (byte) myId;
        short s = seq++;
        p[8] = (byte) (s >> 8);
        p[9] = (byte) s;
        return p;
    }

    private void helloLoop() {
        while (running) {
            try {
                byte[] name = (myName == null ? "Phone" : myName).getBytes(StandardCharsets.UTF_8);
                int n = Math.min(name.length, 60);
                byte[] p = buildHeader(TYPE_HELLO, n);
                System.arraycopy(name, 0, p, HEADER, n);
                sendBroadcast(p, p.length);
                expirePeers();
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                return;
            } catch (Exception ignored) { }
        }
    }

    private void sendBroadcast(byte[] p, int len) throws Exception {
        Set<InetAddress> targets = new HashSet<>();
        targets.add(InetAddress.getByName("255.255.255.255"));
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                if (ia.getBroadcast() != null) targets.add(ia.getBroadcast());
            }
        }
        // Also unicast hello to known peers (helps on hotspots that drop broadcasts).
        synchronized (peers) {
            for (Peer peer : peers.values()) targets.add(peer.addr);
        }
        for (InetAddress a : targets) {
            try { socket.send(new DatagramPacket(p, len, a, PORT)); } catch (Exception ignored) { }
        }
    }

    private void sendAudio(byte[] pcm, int len) {
        if (!running) return;
        byte[] p = buildHeader(TYPE_AUDIO, len);
        System.arraycopy(pcm, 0, p, HEADER, len);
        List<InetAddress> targets = new ArrayList<>();
        synchronized (peers) {
            for (Peer peer : peers.values()) targets.add(peer.addr);
        }
        for (InetAddress a : targets) {
            try { socket.send(new DatagramPacket(p, p.length, a, PORT)); } catch (Exception ignored) { }
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[4096];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                pkt.setLength(buf.length);
                socket.receive(pkt);
                int len = pkt.getLength();
                if (len < HEADER || buf[0] != MAGIC[0] || buf[1] != MAGIC[1]) continue;
                int id = ((buf[4] & 0xff) << 24) | ((buf[5] & 0xff) << 16) | ((buf[6] & 0xff) << 8) | (buf[7] & 0xff);
                if (id == myId) continue;
                if (buf[3] != (byte) channel) continue;
                byte type = buf[2];
                long now = System.currentTimeMillis();

                if (type == TYPE_BYE) {
                    boolean removed;
                    synchronized (peers) { removed = peers.remove(id) != null; }
                    if (removed) publishPeers();
                    continue;
                }

                boolean changed = false;
                Peer peer;
                synchronized (peers) {
                    peer = peers.get(id);
                    if (peer == null) {
                        peer = new Peer();
                        peer.name = "Phone";
                        peers.put(id, peer);
                        changed = true;
                    }
                    peer.addr = pkt.getAddress();
                    peer.lastSeen = now;
                    if (type == TYPE_HELLO) {
                        String n = new String(buf, HEADER, len - HEADER, StandardCharsets.UTF_8);
                        if (!n.equals(peer.name)) { peer.name = n; changed = true; }
                    }
                }
                if (changed) publishPeers();

                if (type == TYPE_AUDIO) {
                    if (now - peer.lastAudio > 500) listener.onTalker(peer.name);
                    peer.lastAudio = now;
                    audio.push(String.valueOf(id), buf, HEADER, len - HEADER);
                }
            } catch (Exception e) {
                if (!running) return;
            }
        }
    }

    private void expirePeers() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        synchronized (peers) {
            java.util.Iterator<Peer> it = peers.values().iterator();
            while (it.hasNext()) {
                if (now - it.next().lastSeen > PEER_TIMEOUT_MS) { it.remove(); changed = true; }
            }
        }
        if (changed) publishPeers();
    }

    private void publishPeers() {
        List<String> names = new ArrayList<>();
        synchronized (peers) {
            for (Peer p : peers.values()) names.add(p.name + "  (" + p.addr.getHostAddress() + ")");
        }
        listener.onPeersChanged(names);
    }
}
