package com.example.medibook.reminders;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Plays the system alarm tone and repeating vibration together, independent of notification
 * channel sound. Both stop automatically after 30 seconds or immediately when Dismiss is tapped.
 * MediaPlayer is primary, Ringtone is the fallback and ToneGenerator is the last-resort audible
 * fallback. No audio-storage permission, foreground service or Clock-app alarm is used.
 */
public final class ReminderAlarmFeedback {
    public static final long DURATION_MS = AlarmFeedbackWindow.DURATION_MS;
    /** Wait, buzz, pause, buzz, pause, long buzz. Index 0 repeats until cancelled. */
    private static final long[] PATTERN = {0, 800, 400, 800, 400, 1200};

    private static final Object LOCK = new Object();
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static MediaPlayer player;
    private static Ringtone ringtone;
    private static ToneGenerator tone;
    private static Vibrator activeVibrator;
    private static PowerManager.WakeLock wakeLock;
    private static final AlarmFeedbackWindow WINDOW = new AlarmFeedbackWindow();
    private static final Runnable CUTOFF = ReminderAlarmFeedback::cutoff;
    private static final Runnable PULSE = ReminderAlarmFeedback::pulse;

    private ReminderAlarmFeedback() { }

    public static void start(Context context, String notificationTag) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            stopInternal();
            WINDOW.begin(notificationTag, SystemClock.elapsedRealtime());
            Uri[] sounds = {RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)};
            for (Uri uri : sounds) {
                if (uri != null && startPlayer(app, uri)) break;
            }
            if (player == null) startRingtone(app, sounds[0]);
            if (player == null && ringtone == null) startTone();
            WINDOW.begin(notificationTag, SystemClock.elapsedRealtime());
            acquireWakeLock(app);
            startVibration(app);
            HANDLER.postDelayed(CUTOFF, DURATION_MS);
            HANDLER.postDelayed(PULSE, 1000L);
        }
    }

    public static void stop(Context context) {
        synchronized (LOCK) { stopInternal(); }
    }

    public static void dismiss(String notificationTag) {
        synchronized (LOCK) { if (WINDOW.stop(notificationTag)) stopInternal(); }
    }

    /** Keep the existing short worker alive while audio runs; never block an Activity/main thread. */
    @androidx.annotation.WorkerThread
    public static void awaitCompletion(String notificationTag) throws InterruptedException {
        synchronized (LOCK) {
            long remaining;
            while ((remaining = WINDOW.remaining(notificationTag, SystemClock.elapsedRealtime())) > 0) {
                LOCK.wait(remaining);
            }
            if (WINDOW.stop(notificationTag)) stopInternal();
        }
    }

    public static boolean hasVibrator(Context context) {
        Vibrator vibrator = vibrator(context);
        return vibrator != null && vibrator.hasVibrator();
    }

    private static void stopInternal() {
        HANDLER.removeCallbacks(CUTOFF);
        HANDLER.removeCallbacks(PULSE);
        WINDOW.stop(null);
        LOCK.notifyAll();
        if (player != null) {
            try { player.stop(); } catch (RuntimeException unavailable) { }
            try { player.release(); } catch (RuntimeException unavailable) { }
            player = null;
        }
        if (ringtone != null) {
            try { if (ringtone.isPlaying()) ringtone.stop(); } catch (RuntimeException unavailable) { }
            ringtone = null;
        }
        if (tone != null) {
            try { tone.stopTone(); } catch (RuntimeException unavailable) { }
            try { tone.release(); } catch (RuntimeException unavailable) { }
            tone = null;
        }
        stopVibration();
        if (wakeLock != null) {
            try { if (wakeLock.isHeld()) wakeLock.release(); } catch (RuntimeException unavailable) { }
            wakeLock = null;
        }
    }

    private static void acquireWakeLock(Context context) {
        try {
            PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (manager != null) {
                wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MediBook:ReminderAudio");
                wakeLock.acquire(DURATION_MS + 1000L);
            }
        } catch (RuntimeException unavailable) { wakeLock = null; }
    }

    private static void pulse() {
        synchronized (LOCK) {
            if (!WINDOW.active(SystemClock.elapsedRealtime())) { stopInternal(); return; }
            try {
                if (ringtone != null && !ringtone.isPlaying()) ringtone.play();
                if (tone != null) tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900);
            } catch (RuntimeException unavailable) {
                if (tone == null) startTone();
            }
            HANDLER.removeCallbacks(PULSE);
            HANDLER.postDelayed(PULSE, 1000L);
        }
    }

    private static void cutoff() {
        synchronized (LOCK) {
            long remaining = WINDOW.remaining(SystemClock.elapsedRealtime());
            // A callback already dequeued for an older alarm must not stop its replacement.
            if (remaining > 0) { HANDLER.removeCallbacks(CUTOFF); HANDLER.postDelayed(CUTOFF, remaining); }
            else stopInternal();
        }
    }

    private static boolean startPlayer(Context context, Uri uri) {
        MediaPlayer next = null;
        try {
            next = new MediaPlayer();
            next.setAudioAttributes(audioAttributes());
            next.setDataSource(context, uri);
            next.setLooping(true);
            next.setOnErrorListener((failed, what, extra) -> {
                synchronized (LOCK) {
                    if (player != failed || !WINDOW.active(SystemClock.elapsedRealtime())) return true;
                    try { failed.release(); } catch (RuntimeException ignored) { }
                    player = null;
                    startRingtone(context, uri);
                    if (ringtone == null) startTone();
                }
                return true;
            });
            next.prepare();
            next.start();
            player = next;
            return true;
        } catch (Exception unavailable) {
            try { if (next != null) next.release(); } catch (RuntimeException ignored) { }
            return false;
        }
    }

    private static void startRingtone(Context context, Uri uri) {
        try {
            Ringtone next = RingtoneManager.getRingtone(context, uri);
            if (next == null) return;
            next.setAudioAttributes(audioAttributes());
            if (Build.VERSION.SDK_INT >= 28) next.setLooping(true);
            next.play();
            if (next.isPlaying()) ringtone = next;
            else next.stop();
        } catch (RuntimeException unavailable) {
            // ToneGenerator below remains available on a device without a playable alarm URI.
        }
    }

    private static void startTone() {
        try {
            tone = new ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME);
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900);
        } catch (RuntimeException unavailable) {
            tone = null;
        }
    }

    private static void startVibration(Context context) {
        Vibrator vibrator = vibrator(context);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        activeVibrator = vibrator;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(PATTERN, 0), audioAttributes());
            } else {
                vibrator.vibrate(PATTERN, 0, audioAttributes());
            }
        } catch (RuntimeException unavailable) {
            // A device may expose a vibrator but reject effects.
        }
    }

    private static void stopVibration() {
        if (activeVibrator == null) return;
        try {
            activeVibrator.cancel();
        } catch (RuntimeException unavailable) {
            // Nothing else to cancel.
        }
        activeVibrator = null;
    }

    private static AudioAttributes audioAttributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
    }

    private static Vibrator vibrator(Context context) {
        if (context == null) return null;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return manager == null ? null : manager.getDefaultVibrator();
        }
        return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }
}
