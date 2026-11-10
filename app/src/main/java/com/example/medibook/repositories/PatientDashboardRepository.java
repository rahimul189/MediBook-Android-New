package com.example.medibook.repositories;

import android.os.Handler;
import android.os.Looper;
import com.example.medibook.models.Appointment;
import com.example.medibook.cache.PatientDataCache;
import com.example.medibook.cache.DashboardCache;
import com.example.medibook.utils.AppointmentSerials;
import com.example.medibook.utils.DashboardPayload;
import com.example.medibook.utils.NextAppointment;
import com.example.medibook.utils.ReservationIdentity;
import com.example.medibook.utils.SlotSerials;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;
import com.google.android.gms.tasks.Task;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** A scoped future-Confirmed query verifies both identity AND nearest appointment, not just an ID. */
public final class PatientDashboardRepository {
    public interface Observer { void changed(Appointment appointment, DashboardState state); }
    public interface Subscription { void close(); }
    public static void clearCachedSummary() { DashboardCache.get().clear(); }

    private static Query query(String owner, long now) {
        return FirebaseFirestore.getInstance().collection("appointments").whereEqualTo("patientUid", owner)
                .whereEqualTo("status", "Confirmed").whereGreaterThan("startAt", new Timestamp(new Date(now)))
                .orderBy("startAt").limit(20);
    }
    private static Appointment select(String owner, QuerySnapshot snapshot) {
        List<Appointment> values = new ArrayList<>();
        for (DocumentSnapshot doc : snapshot) {
            try { values.add(NextAppointment.from(doc.getId(), doc.getData())); }
            catch (IllegalArgumentException invalid) { /* A malformed legacy row is not an eligible appointment. */ }
        }
        Appointment value = NextAppointment.select(owner, values, System.currentTimeMillis());
        // A full invalid/expired prefix is unknown, NOT proof that the patient's future list is empty.
        if (value == null && !snapshot.isEmpty()) throw new IllegalArgumentException("Unverifiable or expired query");
        return value;
    }
    public Subscription watch(String owner, Observer observer) {
        if (!DoctorRepository.sameUser(owner)) { observer.changed(null, DashboardState.ERROR); return () -> { }; }
        Session session = new Session(owner, observer); session.start(); return session::close;
    }
    private static Task<java.util.Map<String, Object>> fresh(String owner) {
        return query(owner, System.currentTimeMillis()).get(Source.SERVER).continueWith(task -> {
            if (!DoctorRepository.sameUser(owner) || !task.isSuccessful() || task.getResult().getMetadata().isFromCache()
                    || task.getResult().getMetadata().hasPendingWrites()) throw new IllegalStateException("Server appointments unavailable");
            return DashboardPayload.next(select(owner, task.getResult()));
        });
    }
    public static void reconcileBestEffort(String owner) {
        if (DoctorRepository.sameUser(owner)) DashboardMirror.once(owner, "dashboards/patients/" + owner, () -> fresh(owner));
    }
    private static final class Session {
        final String owner; final Observer observer; final DashboardMirror mirror;
        final Handler handler = new Handler(Looper.getMainLooper());
        ListenerRegistration appointments, serial;
        Appointment truth;
        boolean closed, known, cached = true, failed, expiredQuery;
        boolean waited;
        final Runnable verificationTimeout = () -> { waited = true; if (active()) render(); };
        int generation, serialVersion;
        long queryAt;
        final Runnable clock = new Runnable() {
            @Override public void run() {
                if (!active()) return;
                if (expiredQuery || (truth != null && !NextAppointment.eligible(owner, truth, System.currentTimeMillis()))) {
                    truth = null; known = false; expiredQuery = false; attach();
                }
                render(); publish(); scheduleClock();
            }
        };
        Session(String owner, Observer observer) {
            this.owner = owner; this.observer = observer;
            DashboardCache.get().bindOwner(owner);
            DashboardCache.Next saved = DashboardCache.get().next(owner, System.currentTimeMillis());
            if (saved != null) { truth = saved.appointment; known = true; }
            mirror = new DashboardMirror(owner, "dashboards/patients/" + owner, () -> fresh(owner), this::render);
        }
        boolean active() { return !closed && DoctorRepository.sameUser(owner); }
        void start() { render(); mirror.start(); attach(); scheduleClock(); handler.postDelayed(verificationTimeout, 8_000L); }
        void scheduleClock() {
            handler.removeCallbacks(clock);
            long delay = truth == null ? 15_000L : Math.max(1, Math.min(15_000L, truth.getStartAt().toDate().getTime() - System.currentTimeMillis()));
            handler.postDelayed(clock, delay);
        }
        void attach() {
            if (appointments != null) appointments.remove();
            final int request = ++generation; queryAt = System.currentTimeMillis();
            appointments = query(owner, queryAt).addSnapshotListener(MetadataChanges.INCLUDE, (snapshot, error) -> {
                if (!active() || request != generation) return;
                if (error != null || snapshot == null) { failed = true; render(); return; }
                if (snapshot.isEmpty() && snapshot.getMetadata().isFromCache()) {
                    cached = true; render(); return; // Do not invent an empty state from an unknown offline cache.
                }
                try {
                    Appointment selected = select(owner, snapshot);
                    boolean same = AppointmentSerials.sameSlot(truth, selected);
                    truth = PatientDataCache.get().withKnownAppointmentSerial(owner, AppointmentSerials.retain(selected, truth));
                    known = true; failed = false;
                    cached = snapshot.getMetadata().isFromCache() || snapshot.getMetadata().hasPendingWrites();
                    // A resumed subscription can start with a cached, still-unnumbered truth.
                    if (!same || serial == null) watchSerial();
                    if (!cached) DashboardCache.get().putNext(owner, truth, System.currentTimeMillis());
                    render(); publish(); scheduleClock();
                } catch (IllegalArgumentException invalid) {
                    failed = true; known = false; truth = null; render();
                    for (DocumentSnapshot doc : snapshot) {
                        Object start = doc.get("startAt");
                        if (start instanceof Timestamp && ((Timestamp) start).toDate().getTime() <= System.currentTimeMillis()) expiredQuery = true;
                    }
                    // Time may have passed between query dispatch and delivery; rebind on the next tick.
                }
            });
        }
        void watchSerial() {
            if (serial != null) serial.remove(); serial = null; final int request = ++serialVersion;
            if (truth == null) return;
            Appointment selected = truth;
            String path = "doctorSlotInventory/" + selected.getDoctorId() + "/dates/" + selected.getAppointmentDate().toDate().getTime()
                    + "/serials/" + ReservationIdentity.slotId(selected.getStartAt().toDate().getTime(), selected.getEndAt().toDate().getTime());
            serial = FirebaseFirestore.getInstance().document(path).addSnapshotListener((snapshot, error) -> {
                if (!active() || request != serialVersion || truth == null || !truth.getId().equals(selected.getId())) return;
                Object number = snapshot == null ? null : snapshot.get("number");
                int value = error == null && snapshot != null && !snapshot.getMetadata().hasPendingWrites()
                        && number instanceof Long && (Long) number > 0 && (Long) number <= SlotSerials.MAX ? ((Long) number).intValue() : 0;
                Appointment resolved = AppointmentSerials.applyLookup(truth, selected, selected.withSlotSerial(value), request, serialVersion);
                if (resolved == truth) return;
                truth = resolved;
                PatientDataCache.get().rememberAppointmentSerial(owner, truth);
                if (!cached) DashboardCache.get().putNext(owner, truth, System.currentTimeMillis());
                render();
            });
        }
        void publish() { if (known && !cached && !failed) mirror.reconcile(DashboardPayload.next(truth)); }
        void render() {
            if (!active()) return;
            Appointment shown = NextAppointment.eligible(owner, truth, System.currentTimeMillis()) ? truth : null;
            if (!known) { observer.changed(null, failed || waited ? DashboardState.ERROR : DashboardState.LOADING); return; }
            DashboardState state = DashboardPayload.state(cached, failed, mirror.connected(), DashboardPayload.next(shown),
                    mirror.summary(), System.currentTimeMillis());
            observer.changed(shown, state);
        }
        void close() {
            closed = true; generation++; serialVersion++; handler.removeCallbacks(clock); mirror.close();
            handler.removeCallbacks(verificationTimeout);
            if (appointments != null) appointments.remove(); if (serial != null) serial.remove();
        }
    }
}
