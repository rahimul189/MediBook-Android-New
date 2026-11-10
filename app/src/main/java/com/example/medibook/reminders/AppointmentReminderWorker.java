package com.example.medibook.reminders;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.medibook.local.LocalPatientServices;
import com.example.medibook.models.Appointment;
import com.example.medibook.repositories.AppointmentMapper;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Source;

import java.util.concurrent.TimeUnit;

/**
 * No appointment names in WorkManager input. Connectivity and a fresh server status are required;
 * uncertainty delays/skips delivery rather than displaying a cached, potentially cancelled booking.
 * The same worker serves the two default reminders and the custom alarm hand-off.
 */
public final class AppointmentReminderWorker extends Worker {
    public AppointmentReminderWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        String uid = getInputData().getString("uid");
        String token = getInputData().getString("epoch");
        String id = getInputData().getString("appointment");
        ReminderSchedule.Kind kind = ReminderSchedule.Kind.from(getInputData().getString("kind"));
        long start = getInputData().getLong("start", -1), due = getInputData().getLong("due", -1);
        if (kind == null) return Result.success();
        long expires = kind == ReminderSchedule.Kind.CUSTOM ? start : start - 5 * ReminderPolicy.MINUTE;
        LocalPatientServices local = LocalPatientServices.get();
        long now = System.currentTimeMillis();
        if (local == null || !AppointmentMapper.isValidId(id) || isStopped()
                || now >= expires
                || !local.matches(uid, token) || !local.remindersEnabled(uid)) return Result.success();
        if (now < due) return Result.retry(); // Wall-clock changes must not cause early reminders.
        if (!local.workerAllowed(uid, token, id, kind, start, due, now)
                || !ReminderNotifications.allowed(getApplicationContext())) return Result.success();
        try {
            DocumentSnapshot doc = Tasks.await(FirebaseFirestore.getInstance().collection("appointments")
                    .document(id).get(Source.SERVER), 25, TimeUnit.SECONDS);
            if (isStopped()) return Result.success();
            if (!doc.exists()) return Result.success();
            if (doc.getMetadata().isFromCache() || doc.getMetadata().hasPendingWrites()) return Result.retry();
            Appointment current = AppointmentMapper.fromSnapshot(doc);
            // The same coordinator also suppresses known cancellation/account races after this read.
            if (!ReminderPolicy.eligible(uid, current, System.currentTimeMillis())) {
                LocalPatientServices.appointmentBestEffort(uid, current);
                return Result.success();
            }
            local.deliver(uid, token, current, kind, start, due);
            // The worker remains alive for at most 30 seconds of alarm feedback, or until Dismiss.
            // Notification-only delivery returns immediately and never enters foreground service mode.
            ReminderAlarmFeedback.awaitCompletion(ReminderNotifications.tag(id, kind));
            return Result.success();
        } catch (InterruptedException interrupted) {
            ReminderAlarmFeedback.dismiss(ReminderNotifications.tag(id, kind));
            Thread.currentThread().interrupt();
            return Result.retry();
        } catch (Exception unavailable) {
            Throwable cause = unavailable;
            for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
                if (cause instanceof IllegalArgumentException) return Result.success();
                if (cause instanceof FirebaseFirestoreException) {
                    FirebaseFirestoreException.Code code = ((FirebaseFirestoreException) cause).getCode();
                    if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                            || code == FirebaseFirestoreException.Code.UNAUTHENTICATED
                            || code == FirebaseFirestoreException.Code.NOT_FOUND) return Result.success();
                }
            }
            return getRunAttemptCount() < 8 && System.currentTimeMillis() < expires
                    ? Result.retry() : Result.success();
        }
    }
}
