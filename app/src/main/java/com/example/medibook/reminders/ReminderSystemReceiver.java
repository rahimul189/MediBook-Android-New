package com.example.medibook.reminders;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.example.medibook.local.LocalPatientServices;

/**
 * Restores custom alarms after a reboot and when exact-alarm access changes. WorkManager already
 * persists default reminders; this receiver only re-registers AlarmManager entries. It never shows
 * a notification directly.
 */
public final class ReminderSystemReceiver extends BroadcastReceiver {
    /** Literal avoids an API 31 constant reference on older runtimes. */
    private static final String ACTION_EXACT_ALARM_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || ACTION_EXACT_ALARM_CHANGED.equals(action)) {
            LocalPatientServices.rescheduleBestEffort();
        }
    }
}
