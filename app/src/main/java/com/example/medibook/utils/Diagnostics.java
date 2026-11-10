package com.example.medibook.utils;

import android.content.pm.ApplicationInfo;

import com.google.firebase.FirebaseApp;

/**
 * Technical detail stays in Logcat, never normal screen copy. Logging cannot break an operation.
 *
 * <p>Flow events (session transitions and operation outcomes)
 * are emitted only for a debuggable build, so a release APK never writes private state to Logcat.
 * No credential, token, contact detail or notification text is ever logged: callers pass counts,
 * enum names and outcome codes only.</p>
 */
public final class Diagnostics {
    private static final String TAG = "MediBook";
    private static Boolean debuggable;

    private Diagnostics() { }

    /** True only for a debuggable build; a missing Android/Firebase runtime answers false. */
    public static boolean debugEnabled() {
        if (debuggable != null) return debuggable;
        try {
            ApplicationInfo info = FirebaseApp.getInstance().getApplicationContext().getApplicationInfo();
            debuggable = (info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (Throwable unavailable) {
            // FirebaseApp may simply not be initialized yet; do not cache that as "not debuggable".
            return false;
        }
        return debuggable;
    }

    /** A bounded state transition for a debug build. Never pass free text or identifiers. */
    public static void event(String area, String detail) {
        if (!debugEnabled()) return;
        try { android.util.Log.i(TAG, area + ": " + detail); }
        catch (RuntimeException unavailableLogger) { /* Also supports local JVM policy tests. */ }
    }

    public static void failure(String operation, Exception error) {
        try {
            if (debugEnabled()) android.util.Log.w(TAG, operation, error);
            else android.util.Log.w(TAG, operation + " failed: " + error.getClass().getSimpleName());
        } catch (RuntimeException unavailableLogger) { /* Also supports local JVM policy tests. */ }
    }
}
