package com.example.medibook.repositories;

import com.example.medibook.models.Doctor;
import com.example.medibook.models.DoctorSchedule;
import com.example.medibook.utils.DoctorScheduleConflicts;
import com.example.medibook.utils.DoctorScheduleValidation;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Online schedule operations only. No deletion, offline write queue or Doctor document mutation. */
public final class DoctorScheduleRepository implements DoctorScheduleStore {
    public enum Error { NONE, NETWORK, PERMISSION, NOT_FOUND, PARENT_MISSING, CONFLICT, OVERLAP,
        ALREADY_EXISTS, INVALID_DATA, SESSION_CHANGED, OUTCOME_UNKNOWN, UNKNOWN }

    public static final class Directory {
        public final Doctor doctor;
        public final List<DoctorSchedule> schedules;

        public Directory(Doctor doctor, List<DoctorSchedule> schedules) {
            this.doctor = doctor;
            List<DoctorSchedule> sorted = new ArrayList<>(schedules);
            sorted.sort(Comparator.comparingInt(DoctorSchedule::getDayOfWeek)
                    .thenComparingInt(DoctorSchedule::getStartMinute).thenComparing(DoctorSchedule::getId));
            this.schedules = Collections.unmodifiableList(sorted);
        }

        public DoctorSchedule find(String id) {
            for (DoctorSchedule entry : schedules) if (entry.getId().equals(id)) return entry;
            return null;
        }
    }

    /** Domain failure, also usable by offline Store fixtures without constructing Android SDK errors. */
    public static final class Failure extends RuntimeException {
        final Error error;
        public Failure(Error error) { super(error.name()); this.error = error; }
    }

    private final FirebaseFirestore firestore = FirebaseFirestore.getInstance();

    @Override public boolean isCurrentUser(String owner) { return DoctorRepository.sameUser(owner); }

    private DocumentReference doctor(String doctorId) { return firestore.collection("doctors").document(doctorId); }
    private CollectionReference schedules(String doctorId) { return doctor(doctorId).collection("schedules"); }

    /** Allocates one stable path without writing; reused after rotation or an ambiguous result. */
    public String newScheduleId(String doctorId) { return schedules(doctorId).document().getId(); }

    public Task<Directory> load(String owner, String doctorId) {
        if (!DoctorRepository.sameUser(owner)) return Tasks.forException(new Failure(Error.SESSION_CHANGED));
        if (!DoctorMapper.isValidId(doctorId)) return Tasks.forException(new Failure(Error.INVALID_DATA));
        return doctor(doctorId).get(Source.SERVER).continueWithTask(parentTask -> {
            if (!parentTask.isSuccessful()) throw parentTask.getException();
            requireOwner(owner);
            DocumentSnapshot parent = parentTask.getResult();
            confirmed(parent);
            if (!parent.exists()) throw new Failure(Error.PARENT_MISSING);
            Doctor selected = DoctorMapper.fromMap(doctorId, parent.getData());
            return schedules(doctorId).get(Source.SERVER).continueWith(task -> {
                if (!task.isSuccessful()) throw task.getException();
                requireOwner(owner);
                QuerySnapshot snapshot = task.getResult();
                if (snapshot.getMetadata().isFromCache() || snapshot.getMetadata().hasPendingWrites()) {
                    throw new Failure(Error.OUTCOME_UNKNOWN);
                }
                List<DoctorSchedule> entries = new ArrayList<>();
                for (DocumentSnapshot document : snapshot.getDocuments()) {
                    confirmed(document);
                    entries.add(DoctorScheduleMapper.fromMap(document.getId(), document.getData()));
                }
                return new Directory(selected, entries);
            });
        });
    }

    public Task<Void> add(String owner, String doctorId, DoctorSchedule draft) {
        return write(owner, doctorId, null, draft, false);
    }

    public Task<Void> edit(String owner, String doctorId, DoctorSchedule original, DoctorSchedule draft) {
        if (original == null || draft == null || !original.getId().equals(draft.getId())) {
            return Tasks.forException(new Failure(Error.INVALID_DATA));
        }
        return write(owner, doctorId, original, draft, false);
    }

    public Task<Void> setActive(String owner, String doctorId, DoctorSchedule original, boolean active) {
        if (original == null) return Tasks.forException(new Failure(Error.INVALID_DATA));
        return write(owner, doctorId, original, original.withActive(active), true);
    }

    private Task<Void> write(String owner, String doctorId, DoctorSchedule original,
                             DoctorSchedule draft, boolean statusOnly) {
        if (!DoctorScheduleValidation.isValid(draft) || (original != null && original.getUpdatedAt() == null)) {
            return Tasks.forException(new Failure(Error.INVALID_DATA));
        }
        // A server preflight catches existing overlaps, including different slot durations.
        return load(owner, doctorId).continueWithTask(preflight -> {
            if (!preflight.isSuccessful()) throw preflight.getException();
            requireOwner(owner);
            Directory latest = preflight.getResult();
            DoctorSchedule latestTarget = latest.find(draft.getId());
            if (original == null && latestTarget != null) throw new Failure(Error.ALREADY_EXISTS);
            if (original != null && latestTarget == null) throw new Failure(Error.NOT_FOUND);
            if (original != null && !DoctorScheduleMapper.sameVersion(original, latestTarget)) {
                throw new Failure(Error.CONFLICT);
            }
            if (DoctorScheduleConflicts.overlaps(draft, latest.schedules)) throw new Failure(Error.OVERLAP);
            DocumentReference target = schedules(doctorId).document(draft.getId());
            return firestore.runTransaction(transaction -> {
                requireOwner(owner);
                DocumentSnapshot parent = transaction.get(doctor(doctorId));
                if (!parent.exists()) throw new Failure(Error.PARENT_MISSING);
                DocumentSnapshot existing = transaction.get(target);
                if (original == null && existing.exists()) throw new Failure(Error.ALREADY_EXISTS);
                if (original != null) {
                    if (!existing.exists()) throw new Failure(Error.NOT_FOUND);
                    if (!DoctorScheduleMapper.sameVersion(original,
                            DoctorScheduleMapper.fromMap(existing.getId(), existing.getData()))) {
                        throw new Failure(Error.CONFLICT);
                    }
                }
                List<DoctorSchedule> current = new ArrayList<>();
                for (DoctorSchedule known : latest.schedules) {
                    if (known.getId().equals(draft.getId())) continue;
                    DocumentSnapshot window = transaction.get(schedules(doctorId).document(known.getId()));
                    if (window.exists()) current.add(DoctorScheduleMapper.fromMap(window.getId(), window.getData()));
                }
                if (DoctorScheduleConflicts.overlaps(draft, current)) throw new Failure(Error.OVERLAP);
                requireOwner(owner);
                if (original == null) {
                    transaction.set(target, DoctorScheduleMapper.writeMap(draft, FieldValue.serverTimestamp()));
                } else {
                    transaction.update(target, statusOnly
                            ? DoctorScheduleMapper.statusMap(draft.isActive(), FieldValue.serverTimestamp())
                            : DoctorScheduleMapper.writeMap(draft, FieldValue.serverTimestamp()));
                }
                // IMPORTANT: a new ID inserted after the outside query is NOT in this read set.
                // Neither this transaction nor the Rules guarantee global overlap exclusion.
                return null;
            });
        });
    }

    private static void confirmed(DocumentSnapshot document) {
        if (document.getMetadata().isFromCache() || document.getMetadata().hasPendingWrites()) {
            throw new Failure(Error.OUTCOME_UNKNOWN);
        }
    }

    private static void requireOwner(String owner) {
        if (!DoctorRepository.sameUser(owner)) throw new Failure(Error.SESSION_CHANGED);
    }

    public static Error errorFor(Exception exception) {
        com.example.medibook.utils.Diagnostics.failure("Schedule operation", exception);
        FirebaseFirestoreException firestoreError = null;
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
            if (cause instanceof Failure) return ((Failure) cause).error;
            if (cause instanceof IllegalArgumentException) return Error.INVALID_DATA;
            if (cause instanceof FirebaseFirestoreException) firestoreError = (FirebaseFirestoreException) cause;
        }
        if (firestoreError != null) {
            switch (firestoreError.getCode()) {
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
