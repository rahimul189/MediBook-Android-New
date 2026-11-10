package com.example.medibook.repositories;

import com.example.medibook.models.Doctor;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Source;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Online-only doctor operations. Deletion is intentionally not exposed. Rules enforce authorization. */
public final class DoctorRepository {
    public enum Error { NONE, NETWORK, PERMISSION, NOT_FOUND, CONFLICT, ALREADY_EXISTS,
        INVALID_DATA, SESSION_CHANGED, OUTCOME_UNKNOWN, UNKNOWN }

    private static final class Failure extends RuntimeException {
        final Error error;
        Failure(Error error) { super(error.name()); this.error = error; }
    }

    private final FirebaseFirestore firestore = FirebaseFirestore.getInstance();

    public static String currentUserId() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user == null ? null : user.getUid();
    }

    public static boolean sameUser(String owner) {
        return owner != null && owner.equals(currentUserId());
    }

    /** Allocates a path locally. It does not create a document until Save is confirmed by the server. */
    public String newDoctorId() { return firestore.collection("doctors").document().getId(); }

    public Task<List<Doctor>> loadDoctors(String owner) {
        if (!sameUser(owner)) return Tasks.forException(new Failure(Error.SESSION_CHANGED));
        return firestore.collection("doctors").get(Source.SERVER).continueWith(task -> {
            if (!task.isSuccessful()) throw task.getException();
            requireOwner(owner);
            if (task.getResult().getMetadata().isFromCache()
                    || task.getResult().getMetadata().hasPendingWrites()) throw new Failure(Error.OUTCOME_UNKNOWN);
            List<Doctor> doctors = new ArrayList<>();
            for (DocumentSnapshot document : task.getResult().getDocuments()) {
                doctors.add(DoctorMapper.fromMap(document.getId(), document.getData()));
            }
            doctors.sort(Comparator.comparing(Doctor::getName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Doctor::getId));
            return doctors;
        });
    }

    public Task<Doctor> loadDoctor(String owner, String id) {
        if (!sameUser(owner)) return Tasks.forException(new Failure(Error.SESSION_CHANGED));
        if (!DoctorMapper.isValidId(id)) return Tasks.forException(new Failure(Error.INVALID_DATA));
        return firestore.collection("doctors").document(id).get(Source.SERVER).continueWith(task -> {
            if (!task.isSuccessful()) throw task.getException();
            requireOwner(owner);
            DocumentSnapshot document = task.getResult();
            if (document.getMetadata().isFromCache() || document.getMetadata().hasPendingWrites()) {
                throw new Failure(Error.OUTCOME_UNKNOWN);
            }
            return readDoctor(document);
        });
    }

    public Task<Void> addDoctor(String owner, Doctor draft) {
        if (!sameUser(owner)) return Tasks.forException(new Failure(Error.SESSION_CHANGED));
        if (!DoctorMapper.isValidId(draft.getId())) return Tasks.forException(new Failure(Error.INVALID_DATA));
        DocumentReference reference = firestore.collection("doctors").document(draft.getId());
        // Transactions need the server: no optimistic set(), offline write queue or automatic UI retry.
        return firestore.<Void>runTransaction(transaction -> {
            requireOwner(owner);
            if (transaction.get(reference).exists()) throw new Failure(Error.ALREADY_EXISTS);
            requireOwner(owner);
            transaction.set(reference, DoctorMapper.createMap(draft, FieldValue.serverTimestamp()));
            return null;
        }).addOnSuccessListener(unused -> com.example.medibook.cache.PatientDataCache.get().invalidateDoctors());
    }

    public Task<Void> editDoctor(String owner, Doctor original, Doctor draft) {
        if (original == null || !original.getId().equals(draft.getId())) {
            return Tasks.forException(new Failure(Error.INVALID_DATA));
        }
        return updateDoctor(owner, original, draft, null);
    }

    /** Explicit target state, never an optimistic toggle or a permanent delete. */
    public Task<Void> setActive(String owner, Doctor original, boolean active) {
        return updateDoctor(owner, original, null, active);
    }

    private Task<Void> updateDoctor(String owner, Doctor original, Doctor draft, Boolean active) {
        if (!sameUser(owner)) return Tasks.forException(new Failure(Error.SESSION_CHANGED));
        if (original == null || !DoctorMapper.isValidId(original.getId())
                || original.getCreatedAt() == null || original.getUpdatedAt() == null) {
            return Tasks.forException(new Failure(Error.INVALID_DATA));
        }
        DocumentReference reference = firestore.collection("doctors").document(original.getId());
        return firestore.<Void>runTransaction(transaction -> {
            requireOwner(owner);
            Doctor current = readDoctor(transaction.get(reference));
            if (!current.getUpdatedAt().equals(original.getUpdatedAt())
                    || !current.getCreatedAt().equals(original.getCreatedAt())) throw new Failure(Error.CONFLICT);
            requireOwner(owner);
            transaction.update(reference, active == null
                    ? DoctorMapper.updateMap(draft, FieldValue.serverTimestamp())
                    : DoctorMapper.statusMap(active, FieldValue.serverTimestamp()));
            return null;
        }).addOnSuccessListener(unused -> com.example.medibook.cache.PatientDataCache.get().invalidateDoctors());
    }

    private static Doctor readDoctor(DocumentSnapshot document) {
        if (!document.exists()) throw new Failure(Error.NOT_FOUND);
        return DoctorMapper.fromMap(document.getId(), document.getData());
    }

    private static void requireOwner(String owner) {
        if (!sameUser(owner)) throw new Failure(Error.SESSION_CHANGED);
    }

    public static Error errorFor(Exception exception) {
        com.example.medibook.utils.Diagnostics.failure("Doctor operation", exception);
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
            if (cause instanceof Failure) return ((Failure) cause).error;
            if (cause instanceof IllegalArgumentException) return Error.INVALID_DATA;
        }
        if (exception instanceof FirebaseFirestoreException) {
            switch (((FirebaseFirestoreException) exception).getCode()) {
                case PERMISSION_DENIED:
                case UNAUTHENTICATED: return Error.PERMISSION;
                case UNAVAILABLE:
                case DEADLINE_EXCEEDED: return Error.NETWORK;
                case NOT_FOUND: return Error.NOT_FOUND;
                case ALREADY_EXISTS: return Error.ALREADY_EXISTS;
                case ABORTED: return Error.CONFLICT;
                default: return Error.UNKNOWN;
            }
        }
        return Error.UNKNOWN;
    }
}
