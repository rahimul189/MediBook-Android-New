package com.example.medibook.repositories;

import android.os.Handler;
import android.os.Looper;
import com.example.medibook.models.Doctor;
import com.example.medibook.models.DoctorSchedule;
import com.example.medibook.utils.PatientScheduleDisplay;
import com.example.medibook.utils.Diagnostics;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldPath;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import java.util.ArrayList;
import java.util.List;

/** Read-only Patient queries. Never calls the Admin directory or accepts cached data as authorized. */
public final class PatientDoctorRepository {
    public interface Callback<T> { void result(T value, DoctorRepository.Error error); }
    private interface Mapper<T> { T map(QuerySnapshot snapshot); }
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    public ListenerRegistration doctors(String owner, Callback<List<Doctor>> callback) {
        Diagnostics.event("Read", "patient directory listener started");
        return watch(owner, db.collection("doctors").whereEqualTo("active", true), snapshot -> {
            List<Doctor> result = new ArrayList<>();
            for (DocumentSnapshot doc : snapshot) result.add(DoctorMapper.fromMap(doc.getId(), doc.getData()));
            return result;
        }, callback);
    }

    public ListenerRegistration doctor(String owner, String id, Callback<Doctor> callback) {
        if (!DoctorMapper.isValidId(id)) throw new IllegalArgumentException("Invalid doctor ID");
        Diagnostics.event("Read", "doctor profile listener started");
        return watch(owner, db.collection("doctors").whereEqualTo("active", true)
                .whereEqualTo(FieldPath.documentId(), id), snapshot -> snapshot.isEmpty() ? null
                : DoctorMapper.fromMap(snapshot.getDocuments().get(0).getId(), snapshot.getDocuments().get(0).getData()), callback);
    }

    public ListenerRegistration schedules(String owner, String id, Callback<List<DoctorSchedule>> callback) {
        if (!DoctorMapper.isValidId(id)) throw new IllegalArgumentException("Invalid doctor ID");
        Diagnostics.event("Read", "doctor schedule listener started");
        return watch(owner, db.collection("doctors").document(id).collection("schedules").whereEqualTo("active", true), snapshot -> {
            List<DoctorSchedule> result = new ArrayList<>();
            for (DocumentSnapshot doc : snapshot) result.add(DoctorScheduleMapper.fromMap(doc.getId(), doc.getData()));
            return PatientScheduleDisplay.sorted(result);
        }, callback);
    }

    private <T> ListenerRegistration watch(String owner, Query query, Mapper<T> mapper, Callback<T> callback) {
        Handler handler = new Handler(Looper.getMainLooper());
        boolean[] closed = {false};
        boolean[] serverSeen = {false};
        boolean[] healthy = {false};
        Runnable timeout = () -> { if (!closed[0]) callback.result(null, DoctorRepository.Error.NETWORK); };
        handler.postDelayed(timeout, 12000);
        ListenerRegistration registration = query.addSnapshotListener(MetadataChanges.INCLUDE, (snapshot, error) -> {
            if (closed[0]) return;
            if (!DoctorRepository.sameUser(owner)) {
                handler.removeCallbacks(timeout);
                callback.result(null, DoctorRepository.Error.SESSION_CHANGED);
                return;
            }
            if (error != null) {
                healthy[0] = false;
                handler.removeCallbacks(timeout);
                callback.result(null, DoctorRepository.errorFor(error));
                return;
            }
            if (snapshot == null || snapshot.getMetadata().isFromCache() || snapshot.getMetadata().hasPendingWrites()) {
                // Wait for server confirmation on first load; clear previously verified data if lost.
                if (serverSeen[0] || snapshot == null) {
                    healthy[0] = false;
                    handler.removeCallbacks(timeout);
                    callback.result(null, DoctorRepository.Error.NETWORK);
                }
                return;
            }
            handler.removeCallbacks(timeout);
            if (serverSeen[0] && healthy[0] && snapshot.getDocumentChanges().isEmpty()) return;
            serverSeen[0] = true;
            try { healthy[0] = true; callback.result(mapper.map(snapshot), DoctorRepository.Error.NONE); }
            catch (IllegalArgumentException invalid) { healthy[0] = false; callback.result(null, DoctorRepository.Error.INVALID_DATA); }
        });
        return () -> { closed[0] = true; handler.removeCallbacks(timeout); registration.remove(); };
    }
}
