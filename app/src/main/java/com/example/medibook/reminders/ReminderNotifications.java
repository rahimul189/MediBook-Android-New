package com.example.medibook.reminders;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.example.medibook.R;
import com.example.medibook.activities.AppointmentDetailsActivity;
import com.example.medibook.models.Appointment;
import com.example.medibook.utils.AppConstants;
import com.example.medibook.utils.SlotSerials;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/**
 * MediBook's own local notification (never the device Clock app). One unique tag per appointment
 * and reminder kind keeps default and custom notifications independently cancellable.
 *
 * <p>Alarm audio and vibration are controlled together for 30 seconds, independently of channel
 * sound. A quiet high-importance channel avoids overlapping system audio that Dismiss cannot stop.</p>
 */
public final class ReminderNotifications {
    public static final String CHANNEL = "appointment_reminders";
    public static final String ALARM_CHANNEL = "appointment_alarms_v2";
    private static final String TAG_PREFIX = "appointment:";

    private ReminderNotifications() { }

    public static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        if (channel == null) {
            channel = new NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel),
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(context.getString(R.string.reminder_channel_description));
            manager.createNotificationChannel(channel);
        }
        NotificationChannel alarm = manager.getNotificationChannel(ALARM_CHANNEL);
        if (alarm == null) {
            alarm = new NotificationChannel(ALARM_CHANNEL, context.getString(R.string.reminder_alarm_channel),
                    NotificationManager.IMPORTANCE_HIGH);
            alarm.setDescription(context.getString(R.string.reminder_alarm_channel_description));
            alarm.enableVibration(false);
            alarm.setSound(null, null);
            manager.createNotificationChannel(alarm);
        }
    }

    public static boolean allowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            NotificationChannel channel = manager == null ? null : manager.getNotificationChannel(CHANNEL);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        }
        return true;
    }

    /** True when the alarm notification channel can be used; audio itself is played separately. */
    public static boolean alarmStyleAvailable(Context context) {
        if (!allowed(context)) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            createChannel(context);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            NotificationChannel channel = manager == null ? null : manager.getNotificationChannel(ALARM_CHANNEL);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        }
        return true;
    }

    public static void show(Context context, String uid, String epoch, Appointment appointment,
                            ReminderSchedule.Kind kind) {
        show(context, uid, epoch, appointment, kind, ReminderMode.NOTIFICATION);
    }

    public static void show(Context context, String uid, String epoch, Appointment appointment,
                            ReminderSchedule.Kind kind, ReminderMode mode) {
        createChannel(context);
        if (!allowed(context) || appointment == null || kind == null) return;
        boolean alarm = ReminderMode.ALARM.equals(mode) && alarmStyleAvailable(context);
        String channel = alarm ? ALARM_CHANNEL : CHANNEL;
        String tag = tag(appointment.getId(), kind);
        int requestCode = Math.abs((epoch + ":" + appointment.getId()).hashCode() % 100_000);
        Intent open = new Intent(context, AppointmentDetailsActivity.class)
                .setData(Uri.parse("medibook://reminder/" + epoch + "/" + Uri.encode(appointment.getId())))
                .putExtra("appointmentId", appointment.getId())
                .putExtra("reminderOwner", uid)
                .putExtra("reminderEpoch", epoch);
        PendingIntent tap = PendingIntent.getActivity(context, requestCode, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent dismiss = PendingIntent.getBroadcast(context, requestCode,
                new Intent(context, ReminderDismissReceiver.class)
                        .setAction(ReminderDismissReceiver.ACTION_DISMISS)
                        .setData(Uri.parse("medibook://reminder-dismiss/" + epoch + "/"
                                + Uri.encode(appointment.getId()) + "/" + kind.wire))
                        .putExtra("notificationTag", tag),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        SimpleDateFormat date = new SimpleDateFormat("d MMM yyyy", Locale.ENGLISH);
        date.setTimeZone(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
        SimpleDateFormat time = new SimpleDateFormat("h:mm a", Locale.ENGLISH);
        time.setTimeZone(TimeZone.getTimeZone(AppConstants.HOSPITAL_TIME_ZONE_ID));
        StringBuilder text = new StringBuilder(context.getString(R.string.reminder_body,
                appointment.getDoctorName(), time.format(appointment.getStartAt().toDate()),
                date.format(appointment.getStartAt().toDate())));
        if (appointment.getSlotSerial() > 0) {
            text.append('\n').append(context.getString(R.string.reminder_slot,
                    SlotSerials.label(appointment.getSlotSerial(), appointment.getStartMinute(),
                            appointment.getEndMinute())));
        }

        NotificationCompat.Builder publicVersion = new NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_calendar_cross)
                .setContentTitle(context.getString(R.string.reminder_title))
                .setContentText(context.getString(R.string.reminder_private_body));
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_calendar_cross)
                .setContentTitle(context.getString(R.string.reminder_title))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(tap)
                .setDeleteIntent(dismiss)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(alarm ? NotificationCompat.CATEGORY_ALARM : NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion.build())
                .addAction(0, context.getString(R.string.reminder_dismiss), dismiss)
                .addAction(0, context.getString(R.string.reminder_view), tap);
        if (alarm) {
            notification.setSilent(true);
            notification.setSound(null);
            notification.setVibrate(new long[]{0});
        }
        try {
            if (alarm) ReminderAlarmFeedback.start(context, tag);
            NotificationManagerCompat.from(context).notify(tag, 0, notification.build());
        } catch (SecurityException revokedPermission) {
            if (alarm) ReminderAlarmFeedback.dismiss(tag);
            // Permission can be revoked between the check and notify().
        }
    }

    public static void cancelAppointment(Context context, String appointmentId) {
        if (appointmentId == null) return;
        ReminderAlarmFeedback.dismiss(tag(appointmentId, ReminderSchedule.Kind.CUSTOM));
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        for (ReminderSchedule.Kind kind : ReminderSchedule.Kind.values()) {
            manager.cancel(tag(appointmentId, kind), 0);
        }
    }

    /** Only the automatic reminders are removed when a custom reminder takes over. */
    public static void cancelDefaults(Context context, String appointmentId) {
        if (appointmentId == null) return;
        NotificationManagerCompat manager = NotificationManagerCompat.from(context);
        manager.cancel(tag(appointmentId, ReminderSchedule.Kind.DEFAULT_24), 0);
        manager.cancel(tag(appointmentId, ReminderSchedule.Kind.DEFAULT_6), 0);
    }

    public static void cancel(Context context, String appointmentId) {
        cancelAppointment(context, appointmentId);
    }

    /** Stops alarm audio and vibration after opening a reminder or ending the account session. */
    public static void stopFeedback(Context context) {
        ReminderAlarmFeedback.stop(context);
    }

    static String tag(String appointmentId, ReminderSchedule.Kind kind) {
        return TAG_PREFIX + appointmentId + ":" + kind.wire;
    }

}
