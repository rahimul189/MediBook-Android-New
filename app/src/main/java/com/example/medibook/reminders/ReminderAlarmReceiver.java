package com.example.medibook.reminders;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Receives the custom AlarmManager trigger and enqueues the same verification worker used by
 * default reminders. The alarm itself never displays private data; the worker rechecks the account
 * epoch and the current server status before notifying.
 */
public final class ReminderAlarmReceiver extends BroadcastReceiver {
    public static final String ACTION_CUSTOM_ALARM = "com.example.medibook.reminder.CUSTOM_ALARM";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_CUSTOM_ALARM.equals(intent.getAction())) return;
        String uid = intent.getStringExtra("uid");
        String epoch = intent.getStringExtra("epoch");
        String appointment = intent.getStringExtra("appointment");
        long start = intent.getLongExtra("start", -1);
        long due = intent.getLongExtra("due", -1);
        if (uid == null || epoch == null || appointment == null || start <= 0 || due <= 0) return;
        Data data = new Data.Builder().putString("uid", uid).putString("epoch", epoch)
                .putString("appointment", appointment)
                .putString("kind", ReminderSchedule.Kind.CUSTOM.wire)
                .putLong("start", start).putLong("due", due).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AppointmentReminderWorker.class)
                .setInputData(data)
                .setInitialDelay(0, TimeUnit.MILLISECONDS)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(com.example.medibook.local.LocalPatientServices.WORK_TAG)
                .addTag(com.example.medibook.local.LocalPatientServices.WORK_TAG + "." + epoch)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                com.example.medibook.local.LocalPatientServices.WORK_TAG + "." + epoch + ".custom.alarm." + appointment,
                ExistingWorkPolicy.REPLACE, request);
    }
}
