package io.freemytv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Soft click for the home screen (moving focus and opening). The app synthesizes the sound itself (a short, low
 * "tock"), writes it once as a WAV in the cache and plays it quietly through SoundPool. It only plays on the home
 * screen, never inside apps.
 * SoundPool.play is a call to the audio server (5 to 16 ms on a KTC TV's MediaTek, Android 11), once per key: played
 * on the UI thread it made the first frames of a focus move stutter, so it plays from a thread of its own.
 */
final class Click {
    private static final int RATE = 48_000;
    private volatile SoundPool pool;
    private int move, open;
    private final HandlerThread thread = new HandlerThread("click", Process.THREAD_PRIORITY_BACKGROUND);
    private final Handler handler;

    Click(Context c) {
        thread.start();
        handler = new Handler(thread.getLooper());
        try {
            pool = new SoundPool.Builder().setMaxStreams(3).setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            move = pool.load(wav(c, "click-move.wav", 1450f, 0.009f, 0.030f), 1);
            open = pool.load(wav(c, "click-open.wav", 880f, 0.022f, 0.090f), 1);
        } catch (IOException | RuntimeException e) {
            AppLog.w("click sound unavailable: " + e.getMessage());
            release();
        }
    }

    void move() {
        if (pool == null || !Prefs.sounds()) return;
        handler.post(() -> {
            SoundPool p = pool;
            if (p != null) p.play(move, 0.14f, 0.14f, 1, 0, 1f);
        });
    }

    void open() {
        if (pool == null || !Prefs.sounds()) return;
        handler.post(() -> {
            SoundPool p = pool;
            if (p != null) p.play(open, 0.18f, 0.18f, 1, 0, 1f);
        });
    }

    void release() {
        SoundPool p = pool;
        pool = null;
        if (p != null) handler.post(p::release);   // after the plays already queued, never during one
        thread.quitSafely();
    }

    /** Sine wave plus one harmonic, 1.5 ms attack (no pop) and exponential decay. */
    private static String wav(Context c, String name, float freq, float decay, float seconds) throws IOException {
        File f = new File(c.getCacheDir(), name);
        if (f.exists() && f.length() > 44) return f.getPath();
        int n = Math.round(RATE * seconds);
        ByteBuffer b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes("US-ASCII")).putInt(36 + n * 2).put("WAVE".getBytes("US-ASCII"));
        b.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes("US-ASCII")).putInt(n * 2);
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double env = Math.min(1, t / 0.0015) * Math.exp(-t / decay);
            double s = 0.75 * Math.sin(2 * Math.PI * freq * t) + 0.25 * Math.sin(2 * Math.PI * freq * 2.01 * t);
            b.putShort((short) Math.round(s * env * 0.7 * Short.MAX_VALUE));
        }
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(b.array());
        }
        return f.getPath();
    }
}
