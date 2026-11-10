package com.example.medibook.repositories;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.example.medibook.R;
import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentStatus;
import com.example.medibook.models.RealtimeAppointmentEvent;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Query;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;

import java.util.HashMap;
import java.util.Map;

/** Optional foreground display transport. No result here participates in a Firestore operation. */
public final class RealtimeDisplayRepository {
    private static final String TAG = "MediBookLiveDisplay";
    private static FirebaseApp rootApp;
    private static String rootUrl;
    private static DatabaseReference sharedRoot;
    public interface Subscription { void close(); }
    public interface DashboardObserver { void changed(int status, @Nullable Appointment latest); }
    public interface QueueObserver { void changed(int status, String tokens); }
    private RealtimeDisplayRepository() { }

    @Nullable static synchronized DatabaseReference root() {
        try {
            FirebaseApp app = FirebaseApp.getInstance();
            String url = app.getApplicationContext().getString(R.string.medibook_database_url).trim();
            if (url.isEmpty()) url = app.getOptions().getDatabaseUrl();
            if (url == null || url.isEmpty()) return null;
            // No guessed region/instance and no production data/settings changes.
            if (!url.matches("https://[A-Za-z0-9-]+\\.(firebaseio\\.com|[A-Za-z0-9-]+\\.firebasedatabase\\.app)/?")) return null;
            // One client/root for all foreground projections. In particular, useEmulator mutates
            // the SDK's RepoInfo key: repeated getInstance(originalUrl) must not select a second
            // instance after that mutation. Authentication and Rules still apply to every operation.
            if (sharedRoot == null || rootApp != app || !url.equals(rootUrl)) {
                sharedRoot = FirebaseDatabase.getInstance(app, url).getReference();
                rootApp = app; rootUrl = url;
            }
            return sharedRoot;
        } catch (RuntimeException unavailable) { return null; }
    }

    /** Called only AFTER Firestore server reconciliation. Swallow optional setup/network errors. */
    public static void publishBestEffort(String owner, Appointment appointment) {
        try {
            DatabaseReference root = root();
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (root == null || user == null || !DoctorRepository.sameUser(owner)) return;
            user.getIdToken(false).addOnSuccessListener(token -> {
                if (!DoctorRepository.sameUser(owner)) return;
                boolean admin = Boolean.TRUE.equals(token.getClaims().get("admin"));
                // A Patient can affect only their own status branch, never an Admin/shared feed.
                if (admin || owner.equals(appointment.getPatientUid())) {
                    publish(root, owner, appointment, admin);
                    // Separate, bounded, best-effort read-model reconciliation; never gates Firestore.
                    if (admin) AdminDashboardRepository.reconcileBestEffort(owner);
                    else PatientDashboardRepository.reconcileBestEffort(owner);
                }
            }).addOnFailureListener(error -> Log.w(TAG, "Optional display token unavailable"));
        } catch (RuntimeException optionalFailure) { Log.w(TAG, "Optional display unavailable"); }
    }

    private static void publish(DatabaseReference root, String owner, Appointment appointment, boolean admin) {
        try {
            RealtimeAppointmentEvent event = RealtimeAppointmentEvent.fromAppointment(appointment, false);
            if (!RealtimeAppointmentEvent.validKey(appointment.getPatientUid())) return;
            writeNewer(root.child("display/status").child(appointment.getPatientUid()).child(event.id), owner, event.toMap());
            if (!admin) return;
            if (appointment.getStatus() == AppointmentStatus.CONFIRMED) {
                RealtimeAppointmentEvent booking = RealtimeAppointmentEvent.fromAppointment(appointment, true);
                writeNewer(root.child("display/bookings").child(event.id), owner, booking.toMap());
            }
            // Only token/status/time: no appointment IDs, patient names, UIDs or phone numbers.
            writeNewer(root.child("display/queues").child(event.doctorId).child(event.dateKey).child(event.queueToken), owner, event.queueMap());
        } catch (RuntimeException optionalFailure) { Log.w(TAG, "Optional display write unavailable"); }
    }

    private static void writeNewer(DatabaseReference ref, String owner, Map<String, Object> values) {
        ref.runTransaction(new Transaction.Handler() {
            @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData current) {
                if (!DoctorRepository.sameUser(owner)) return Transaction.abort();
                if (!RealtimeAppointmentEvent.shouldReplace(current.getValue(), values)) return Transaction.abort();
                current.setValue(values); return Transaction.success(current);
            }
            @Override public void onComplete(@Nullable DatabaseError error, boolean committed, @Nullable DataSnapshot snapshot) {
                if (error != null) Log.w(TAG, "Optional display write code=" + error.getCode());
            }
        }, false); // No speculative local display events.
    }

    public static Subscription dashboard(String owner, boolean admin, DashboardObserver observer) {
        DatabaseReference root = root();
        if (root == null) { observer.changed(R.string.live_not_configured, null); return () -> { }; }
        Dashboard session = new Dashboard(root, owner, admin, observer);
        session.start(); return session;
    }

    private static final class Dashboard implements Subscription {
        final DatabaseReference root;
        final String owner;
        final boolean admin;
        final DashboardObserver observer;
        final Query feed;
        final Map<String, String> mirrored = new HashMap<>();
        ListenerRegistration bridge;
        boolean closed, connected;
        int version;
        String fingerprint;
        Appointment latest;
        ValueEventListener data, connection;

        Dashboard(DatabaseReference root, String owner, boolean admin, DashboardObserver observer) {
            this.root = root; this.owner = owner; this.admin = admin; this.observer = observer;
            feed = root.child(admin ? "display/bookings" : "display/status/" + owner).orderByChild("changedAt").limitToLast(20);
        }
        boolean active() { return !closed && DoctorRepository.sameUser(owner); }
        void start() {
            observer.changed(R.string.live_connecting, null);
            data = new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snapshot) { receive(snapshot); }
                @Override public void onCancelled(@NonNull DatabaseError error) {
                    if (active()) observer.changed(R.string.live_rules_error, null);
                }
            };
            feed.addValueEventListener(data);
            connection = new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                    if (!active()) return;
                    connected = Boolean.TRUE.equals(snapshot.getValue(Boolean.class));
                    if (!connected) { observer.changed(R.string.live_offline, latest); return; }
                    // SDK reconnects the subscription; explicitly retry a failed server verification.
                    fingerprint = null;
                    feed.get().addOnSuccessListener(Dashboard.this::receive);
                    if (admin) {
                        mirrored.clear();
                        FirebaseFirestore.getInstance().collection("appointments").orderBy("updatedAt").limitToLast(20)
                                .get(com.google.firebase.firestore.Source.SERVER).addOnSuccessListener(Dashboard.this::mirror);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError error) { }
            };
            root.child(".info/connected").addValueEventListener(connection);
            if (admin) startAdminBridge();
        }
        void startAdminBridge() {
            // A bounded foreground reconciliation, NOT a privileged automatic backend mirror.
            bridge = FirebaseFirestore.getInstance().collection("appointments").orderBy("updatedAt").limitToLast(20)
                    .addSnapshotListener(MetadataChanges.INCLUDE, (snapshot, error) -> {
                        if (!active()) return;
                        if (error != null) { observer.changed(R.string.live_bridge_error, latest); return; }
                        if (snapshot == null || snapshot.getMetadata().isFromCache() || snapshot.getMetadata().hasPendingWrites()) return;
                        mirror(snapshot);
                    });
        }
        void mirror(com.google.firebase.firestore.QuerySnapshot snapshot) {
            if (!active() || snapshot.getMetadata().isFromCache() || snapshot.getMetadata().hasPendingWrites()) return;
            for (com.google.firebase.firestore.DocumentSnapshot doc : snapshot) {
                try {
                    Appointment a = AppointmentMapper.fromSnapshot(doc);
                    String signature = RealtimeAppointmentEvent.fromAppointment(a, false).fingerprint();
                    if (!signature.equals(mirrored.get(a.getId()))) {
                        mirrored.put(a.getId(), signature); publish(root, owner, a, true);
                    }
                } catch (IllegalArgumentException invalid) { Log.w(TAG, "Invalid Firestore display source skipped"); }
            }
            // Bound memory even during long dashboard sessions.
            if (mirrored.size() > 100) mirrored.clear();
        }
        void receive(DataSnapshot snapshot) {
            if (!active()) return;
            RealtimeAppointmentEvent newest = null;
            for (DataSnapshot child : snapshot.getChildren()) {
                try {
                    RealtimeAppointmentEvent e = RealtimeAppointmentEvent.fromMap(child.getKey(), child.getValue());
                    if (newest == null || e.changedAt >= newest.changedAt) newest = e;
                } catch (IllegalArgumentException invalid) { /* Never render malformed hints. */ }
            }
            if (newest == null) { version++; latest = null; fingerprint = null; observer.changed(R.string.live_empty, null); return; }
            if (newest.fingerprint().equals(fingerprint)) return; // One card, never duplicate notifications.
            fingerprint = newest.fingerprint();
            RealtimeAppointmentEvent event = newest;
            int request = ++version;
            new AppointmentRepository().get(owner, event.id).addOnCompleteListener(task -> {
                if (!active() || request != version) return;
                if (task.isSuccessful() && event.matches(task.getResult(), owner, admin)) {
                    latest = task.getResult(); observer.changed(connected ? R.string.live_verified : R.string.live_offline, latest);
                    new SlotSerialRepository().appointments(owner, java.util.Collections.singletonList(latest)).addOnSuccessListener(values -> {
                        if (!active() || request != version) return;
                        latest = values.get(0); observer.changed(connected ? R.string.live_verified : R.string.live_offline, latest);
                    });
                } else {
                    fingerprint = null; latest = null; observer.changed(R.string.live_unverified, null);
                }
            });
        }
        @Override public void close() {
            closed = true; version++;
            if (data != null) feed.removeEventListener(data);
            if (connection != null) root.child(".info/connected").removeEventListener(connection);
            if (bridge != null) bridge.remove();
            mirrored.clear(); latest = null;
        }
    }

    public static Subscription queue(String owner, String doctorId, long date, QueueObserver observer) {
        DatabaseReference root = root();
        if (root == null) { observer.changed(R.string.live_not_configured, ""); return () -> { }; }
        if (!RealtimeAppointmentEvent.validKey(doctorId) || date < 0) return () -> { };
        DatabaseReference ref = root.child("display/queues").child(doctorId).child(Long.toString(date));
        final boolean[] closed = {false};
        final String[] shown = {""};
        final int[] generation = {0};
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (closed[0] || !DoctorRepository.sameUser(owner)) return;
                int request = ++generation[0];
                new SlotSerialRepository().numbers(owner, doctorId, date).addOnSuccessListener(numbers -> {
                if (closed[0] || request != generation[0] || !DoctorRepository.sameUser(owner)) return;
                StringBuilder rows = new StringBuilder(); int count = 0;
                for (DataSnapshot child : snapshot.getChildren()) {
                    if (!RealtimeAppointmentEvent.validToken(child.getKey()) || !RealtimeAppointmentEvent.validStatus(child.child("status").getValue())) continue;
                    if (rows.length() > 0) rows.append("\n");
                    rows.append("Appointment Slot No.: ").append(com.example.medibook.utils.SlotSerials.legacyDisplay(child.getKey(), date, numbers))
                            .append(" · ").append(child.child("status").getValue());
                    if (++count == 4) { rows.append(" …"); break; }
                }
                shown[0] = rows.toString(); observer.changed(R.string.live_queue_hint, shown[0]);
                });
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                if (!closed[0] && DoctorRepository.sameUser(owner)) observer.changed(R.string.live_rules_error, "");
            }
        };
        ref.addValueEventListener(listener);
        ValueEventListener connection = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (closed[0] || !DoctorRepository.sameUser(owner)) return;
                if (!Boolean.TRUE.equals(snapshot.getValue(Boolean.class))) observer.changed(R.string.live_offline, shown[0]);
                else ref.get().addOnSuccessListener(listener::onDataChange);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { }
        };
        root.child(".info/connected").addValueEventListener(connection);
        return () -> {
            closed[0] = true; ref.removeEventListener(listener); root.child(".info/connected").removeEventListener(connection);
        };
    }
}
