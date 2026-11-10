package com.example.medibook.repositories;

import android.os.Handler;
import android.os.Looper;
import com.example.medibook.utils.DashboardPayload;
import com.example.medibook.utils.WeeklyAppointmentStats;
import com.example.medibook.cache.DashboardCache;
import java.util.Map;
import com.google.firebase.firestore.Source;

/** RTDB is the primary verified projection; the tested Firestore aggregator remains the fallback. */
public final class AdminDashboardRepository {
    public interface Observer { void changed(WeeklyAppointmentStats stats, DashboardState state); }
    public interface Subscription { void close(); }
    public Subscription watch(String owner, Observer observer) {
        if (!DoctorRepository.sameUser(owner)) { observer.changed(null, DashboardState.ERROR); return () -> { }; }
        Session session = new Session(owner, observer); session.start(); return session::close;
    }
    public static void reconcileBestEffort(String owner) {
        if (!DoctorRepository.sameUser(owner)) return;
        DashboardMirror.once(owner, "dashboards/admin", () -> {
            long week = WeeklyAppointmentStats.weekStart(System.currentTimeMillis());
            return WeeklyAnalyticsRepository.query(System.currentTimeMillis()).get(Source.SERVER).continueWith(WeeklyAnalyticsRepository.COUNTER, task -> {
                if (!DoctorRepository.sameUser(owner) || !task.isSuccessful() || task.getResult().getMetadata().isFromCache()
                        || task.getResult().getMetadata().hasPendingWrites() || week != WeeklyAppointmentStats.weekStart(System.currentTimeMillis())) {
                    throw new IllegalStateException("Server analytics unavailable");
                }
                return DashboardPayload.analytics(WeeklyAnalyticsRepository.count(task.getResult(), System.currentTimeMillis()));
            });
        });
    }
    private static final class Session {
        final String owner; final Observer observer; final DashboardMirror mirror;
        final Handler handler = new Handler(Looper.getMainLooper());
        WeeklyAnalyticsRepository.Subscription firestore;
        WeeklyAppointmentStats truth;
        boolean cached = true, failed, closed;
        boolean waited;
        final Runnable verificationTimeout = () -> { waited = true; if (active()) render(); };
        final Runnable freshness = new Runnable() {
            @Override public void run() {
                if (!active()) return; render(); publish(); handler.postDelayed(this, 30_000L);
            }
        };
        Session(String owner, Observer observer) {
            this.owner = owner; this.observer = observer;
            DashboardCache.get().bindOwner(owner);
            truth = DashboardCache.get().analytics(owner, System.currentTimeMillis());
            mirror = new DashboardMirror(owner, "dashboards/admin", () -> {
                long week = WeeklyAppointmentStats.weekStart(System.currentTimeMillis());
                return WeeklyAnalyticsRepository.query(System.currentTimeMillis()).get(Source.SERVER).continueWith(WeeklyAnalyticsRepository.COUNTER, task -> {
                    if (!active() || !task.isSuccessful()) throw new IllegalStateException("Server analytics unavailable");
                    if (task.getResult().getMetadata().isFromCache() || task.getResult().getMetadata().hasPendingWrites()
                            || week != WeeklyAppointmentStats.weekStart(System.currentTimeMillis())) throw new IllegalStateException("Stale period");
                    return DashboardPayload.analytics(WeeklyAnalyticsRepository.count(task.getResult(), System.currentTimeMillis()));
                });
            }, this::render);
        }
        boolean active() { return !closed && DoctorRepository.sameUser(owner); }
        void start() {
            render(); mirror.start();
            firestore = new WeeklyAnalyticsRepository().watch(owner, (stats, fromCache, error) -> {
                if (!active()) return;
                failed = error != WeeklyAnalyticsRepository.Error.NONE;
                if (stats != null) {
                    truth = stats; cached = fromCache;
                    if (!cached && !failed) DashboardCache.get().putAnalytics(owner, stats, System.currentTimeMillis());
                }
                render(); publish();
            });
            handler.postDelayed(freshness, 30_000L);
            handler.postDelayed(verificationTimeout, 8_000L);
        }
        void publish() { if (!cached && !failed && current()) mirror.reconcile(DashboardPayload.analytics(truth)); }
        boolean current() {
            return truth != null && truth.dayStarts[truth.todayIndex] == WeeklyAppointmentStats.dayStart(System.currentTimeMillis());
        }
        void render() {
            if (!active()) return;
            if (!current()) { observer.changed(null, failed || waited ? DashboardState.ERROR : DashboardState.LOADING); return; }
            Map<String, Object> expected = DashboardPayload.analytics(truth);
            if (!cached && !failed && mirror.usable(expected)) {
                try { observer.changed(DashboardPayload.stats(mirror.summary(), System.currentTimeMillis()), DashboardState.LIVE); return; }
                catch (IllegalArgumentException invalid) { /* Fall back, never render unverified counts. */ }
            }
            observer.changed(truth, cached || failed ? DashboardState.CACHED : DashboardState.FIRESTORE);
        }
        void close() { closed = true; handler.removeCallbacks(freshness); handler.removeCallbacks(verificationTimeout); mirror.close(); if (firestore != null) firestore.close(); }
    }
}
