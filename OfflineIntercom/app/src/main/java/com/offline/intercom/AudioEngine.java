package com.offline.intercom;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Microphone capture + multi-speaker playback with a small jitter buffer and mixer.
 * Audio is raw 16-bit mono PCM; frames are 20 ms long.
 */
public class AudioEngine {

    public interface FrameSink {
        void onFrame(byte[] pcm, int len);
    }

    private static final int FRAME_MS = 20;
    private static final int PREFILL_FRAMES = 3;   // ~60 ms jitter buffer
    private static final int MAX_QUEUE = 12;       // drop old audio beyond ~240 ms

    private final int sampleRate;
    private final int frameBytes;
    private final FrameSink sink;

    private volatile boolean running;
    private volatile boolean transmitting;
    private volatile boolean muteWhileTalking = true;

    private Thread captureThread;
    private Thread playThread;

    private static class Source {
        final ArrayDeque<byte[]> queue = new ArrayDeque<>();
        boolean primed;
        long lastSeen;
    }

    private final Map<String, Source> sources = new HashMap<>();

    public AudioEngine(int sampleRate, FrameSink sink) {
        this.sampleRate = sampleRate;
        this.frameBytes = sampleRate * FRAME_MS / 1000 * 2;
        this.sink = sink;
    }

    public int getFrameBytes() { return frameBytes; }

    public void setTransmitting(boolean on) { transmitting = on; }

    public boolean isTransmitting() { return transmitting; }

    /** Half-duplex (walkie-talkie) when true: incoming audio is silenced while you talk. */
    public void setMuteWhileTalking(boolean mute) { muteWhileTalking = mute; }

    public void start() {
        if (running) return;
        running = true;
        captureThread = new Thread(this::captureLoop, "mic");
        playThread = new Thread(this::playLoop, "speaker");
        captureThread.start();
        playThread.start();
    }

    public void stop() {
        running = false;
        transmitting = false;
        if (captureThread != null) captureThread.interrupt();
        if (playThread != null) playThread.interrupt();
        synchronized (sources) { sources.clear(); }
    }

    /** Feed a received frame from a remote speaker. */
    public void push(String sourceId, byte[] data, int off, int len) {
        if (!running || len <= 0) return;
        byte[] frame = new byte[frameBytes];
        System.arraycopy(data, off, frame, 0, Math.min(len, frameBytes));
        synchronized (sources) {
            Source s = sources.get(sourceId);
            if (s == null) {
                s = new Source();
                sources.put(sourceId, s);
            }
            s.lastSeen = System.currentTimeMillis();
            s.queue.addLast(frame);
            while (s.queue.size() > MAX_QUEUE) s.queue.pollFirst();
        }
    }

    @SuppressLint("MissingPermission")
    private void captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        int minBuf = AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf, frameBytes * 4));
        } catch (Exception e) {
            return;
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            return;
        }
        int session = rec.getAudioSessionId();
        AcousticEchoCanceler aec = null;
        NoiseSuppressor ns = null;
        AutomaticGainControl agc = null;
        try {
            if (AcousticEchoCanceler.isAvailable()) { aec = AcousticEchoCanceler.create(session); if (aec != null) aec.setEnabled(true); }
            if (NoiseSuppressor.isAvailable()) { ns = NoiseSuppressor.create(session); if (ns != null) ns.setEnabled(true); }
            if (AutomaticGainControl.isAvailable()) { agc = AutomaticGainControl.create(session); if (agc != null) agc.setEnabled(true); }
        } catch (Exception ignored) { }

        byte[] buf = new byte[frameBytes];
        try {
            rec.startRecording();
            while (running) {
                int n = 0;
                while (n < frameBytes && running) {
                    int r = rec.read(buf, n, frameBytes - n);
                    if (r <= 0) break;
                    n += r;
                }
                if (n == frameBytes && transmitting) {
                    try { sink.onFrame(buf, n); } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) {
        } finally {
            try { rec.stop(); } catch (Exception ignored) { }
            rec.release();
            if (aec != null) aec.release();
            if (ns != null) ns.release();
            if (agc != null) agc.release();
        }
    }

    private void playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        int minBuf = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(minBuf, frameBytes * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        int samples = frameBytes / 2;
        int[] mix = new int[samples];
        byte[] out = new byte[frameBytes];
        try {
            track.play();
            while (running) {
                java.util.Arrays.fill(mix, 0);
                long now = System.currentTimeMillis();
                synchronized (sources) {
                    Iterator<Map.Entry<String, Source>> it = sources.entrySet().iterator();
                    while (it.hasNext()) {
                        Source s = it.next().getValue();
                        if (s.queue.isEmpty()) {
                            s.primed = false;
                            if (now - s.lastSeen > 10000) it.remove();
                            continue;
                        }
                        if (!s.primed) {
                            if (s.queue.size() < PREFILL_FRAMES) continue;
                            s.primed = true;
                        }
                        byte[] f = s.queue.pollFirst();
                        for (int i = 0; i < samples; i++) {
                            mix[i] += (short) ((f[2 * i] & 0xff) | (f[2 * i + 1] << 8));
                        }
                    }
                }
                boolean silence = transmitting && muteWhileTalking;
                for (int i = 0; i < samples; i++) {
                    int v = silence ? 0 : mix[i];
                    if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
                    if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
                    out[2 * i] = (byte) v;
                    out[2 * i + 1] = (byte) (v >> 8);
                }
                // Blocking write paces this loop at real time.
                track.write(out, 0, frameBytes);
            }
        } catch (Exception ignored) {
        } finally {
            try { track.stop(); } catch (Exception ignored) { }
            track.release();
        }
    }
}
