package com.example.medibook.repositories;

import com.example.medibook.utils.WeeklyAppointmentStats;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;

import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.google.android.gms.tasks.Tasks;
import android.os.Handler;
import android.os.Looper;
import com.google.firebase.firestore.FirebaseFirestoreException;

/**
 * Appointment Overview data for the Admin dashboard.
 *
 * <p>One bounded range query covers exactly the seven scheduled Dhaka days of the current
 * Saturday-to-Friday week and is reused for the chart and both totals, so no historical
 * appointment collection is read. The snapshot listener reports cached data first and refreshes
 * quietly; the week is recomputed on every emission and the listener is re-subscribed when the week
 * rolls over at Dhaka midnight.</p>
 */
public final class WeeklyAnalyticsRepository {
    static final ExecutorService COUNTER = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "MediBookAnalytics"); thread.setDaemon(true); return thread;
    });
    public enum Error { NONE, PERMISSION, NETWORK, UNKNOWN }

    public interface Listener {
        void changed(WeeklyAppointmentStats stats, boolean fromCache, Error error);
    }
    public interface Subscription { void close(); }

    public static Error errorFor(Exception exception) {
        if (exception == null) return Error.NONE;
        if (exception instanceof FirebaseFirestoreException) {
            switch (((FirebaseFirestoreException) exception).getCode()) {
                case PERMISSION_DENIED: case UNAUTHENTICATED: return Error.PERMISSION;
                case UNAVAILABLE: case DEADLINE_EXCEEDED: return Error.NETWORK;
                default: break;
            }
        }
        return Error.UNKNOWN;
    }

    public static Query query(long now) {
        long start = WeeklyAppointmentStats.weekStart(now);
        return FirebaseFirestore.getInstance().collection("appointments")
                .whereGreaterThanOrEqualTo("appointmentDate", new Timestamp(new Date(start)))
                .whereLessThan("appointmentDate", new Timestamp(new Date(start + 7 * 86_400_000L)));
    }

    public static WeeklyAppointmentStats count(QuerySnapshot snapshot, long now) {
        return aggregate(snapshot, now).stats;
    }

    private static final class Counted {
        final WeeklyAppointmentStats stats; final long countedAt, validUntil;
        Counted(WeeklyAppointmentStats stats, long countedAt, long validUntil) {
            this.stats = stats; this.countedAt = countedAt; this.validUntil = validUntil;
        }
    }
    private static Counted aggregate(QuerySnapshot snapshot, long now) {
        WeeklyAppointmentStats.Builder builder = WeeklyAppointmentStats.builder(now);
        long today = WeeklyAppointmentStats.dayStart(now), nextChange = WeeklyAppointmentStats.nextDay(today);
        for (DocumentSnapshot document : snapshot) {
            Object date = document.get("appointmentDate"), status = document.get("status"), end = document.get("endAt");
            if (!(date instanceof Timestamp) || !(status instanceof String) || !(end instanceof Timestamp)) continue;
            builder.add(((Timestamp) date).toDate().getTime(), (String) status, ((Timestamp) end).toDate().getTime());
            long endAt = ((Timestamp) end).toDate().getTime();
            if (((Timestamp) date).toDate().getTime() == today && "Confirmed".equals(status) && endAt > now) {
                nextChange = Math.min(nextChange, endAt);
            }
        }
        return new Counted(builder.build(), now, nextChange);
    }

    public Subscription watch(String owner, Listener listener) {
        if (!DoctorRepository.sameUser(owner)) {
            listener.changed(null, false, Error.PERMISSION);
            return () -> { };
        }
        Session session = new Session(owner, listener);
        session.start();
        return session;
    }

    private static final class Session implements Subscription {
        private final String owner;
        private final Listener listener;
        private ListenerRegistration registration;
        private long weekStart;
        private boolean closed;
        private QuerySnapshot latest;
        private int generation;
        private int computation;
        private boolean failed;
        private int contentVersion, countedContentVersion = -1;
        private Counted counted;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable clock = new Runnable() {
            @Override public void run() {
                if (closed || !DoctorRepository.sameUser(owner)) return;
                if (WeeklyAppointmentStats.weekStart(System.currentTimeMillis()) != weekStart) { start(); return; }
                else if (latest != null && !failed) emit(latest);
                handler.postDelayed(this, 30_000L);
            }
        };

        Session(String owner, Listener listener) {
            this.owner = owner;
            this.listener = listener;
        }

        void start() {
            remove();
            int request = ++generation;
            weekStart = WeeklyAppointmentStats.weekStart(System.currentTimeMillis());
            latest = null;
            counted = null; countedContentVersion = -1; contentVersion++; computation++; failed = false;
            Query query = query(System.currentTimeMillis());
            final long week = weekStart;
            registration = query.addSnapshotListener(MetadataChanges.INCLUDE, (snapshot, error) -> {
                if (closed || request != generation || !DoctorRepository.sameUser(owner)) return;
                if (error != null) {
                    failed = true; computation++;
                    listener.changed(null, false, errorFor(error));
                    return;
                }
                if (snapshot == null) return;
                if (latest == null && snapshot.isEmpty() && snapshot.getMetadata().isFromCache()) {
                    listener.changed(null, true, Error.NONE); return; // Unknown cache is not a real zero.
                }
                if (WeeklyAppointmentStats.weekStart(System.currentTimeMillis()) != week) {
                    // Dhaka midnight moved the week boundary while this listener was open.
                    start();
                    return;
                }
                if (latest == null || !snapshot.getDocumentChanges().isEmpty()) contentVersion++;
                failed = false; latest = snapshot; emit(snapshot);
            });
            handler.removeCallbacks(clock); handler.postDelayed(clock, 30_000L);
        }

        private void emit(QuerySnapshot snapshot) {
            long now = System.currentTimeMillis();
            boolean unchanged = countedContentVersion == contentVersion;
            if (counted != null && unchanged && now >= counted.countedAt && now < counted.validUntil
                    && counted.stats.dayStarts[counted.stats.todayIndex] == WeeklyAppointmentStats.dayStart(now)) {
                deliver(counted.stats, snapshot); return;
            }
            int version = ++computation, queryVersion = generation, dataVersion = contentVersion;
            Tasks.call(COUNTER, () -> aggregate(snapshot, now)).addOnCompleteListener(result -> {
                if (closed || failed || version != computation || queryVersion != generation
                        || !DoctorRepository.sameUser(owner)) return;
                if (!result.isSuccessful()) { listener.changed(null, false, Error.UNKNOWN); return; }
                if (weekStart != WeeklyAppointmentStats.weekStart(System.currentTimeMillis())) { start(); return; }
                counted = result.getResult(); countedContentVersion = dataVersion;
                if (System.currentTimeMillis() >= counted.validUntil
                        || counted.stats.dayStarts[counted.stats.todayIndex] != WeeklyAppointmentStats.dayStart(System.currentTimeMillis())) {
                    emit(snapshot); return;
                }
                deliver(counted.stats, snapshot);
            });
        }

        private void deliver(WeeklyAppointmentStats stats, QuerySnapshot snapshot) {
            listener.changed(stats, snapshot.getMetadata().isFromCache() || snapshot.getMetadata().hasPendingWrites(), Error.NONE);
        }

        private void remove() {
            if (registration != null) registration.remove();
            registration = null;
        }

        @Override public void close() {
            closed = true;
            generation++; computation++; handler.removeCallbacks(clock);
            remove();
        }
    }
}
