package com.example.medibook.reminders;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationManagerCompat;

/** Handles the Dismiss action without opening an Activity or changing any appointment data. */
public final class ReminderDismissReceiver extends BroadcastReceiver {
    public static final String ACTION_DISMISS = "com.example.medibook.reminder.DISMISS";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_DISMISS.equals(intent.getAction())) return;
        String tag = intent.getStringExtra("notificationTag");
        if (tag == null) return;
        ReminderAlarmFeedback.dismiss(tag);
        try {
            NotificationManagerCompat.from(context).cancel(tag, 0);
        } catch (SecurityException revokedPermission) {
            // Nothing to cancel when the notification surface is unavailable.
        }
    }
}
