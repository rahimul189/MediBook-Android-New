package com.example.medibook.repositories;

import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentSlot;
import com.example.medibook.models.AppointmentStatus;
import com.example.medibook.models.BookingLock;
import com.example.medibook.models.Doctor;
import com.example.medibook.models.DoctorSchedule;
import com.example.medibook.models.InventorySlot;
import com.example.medibook.utils.AppointmentPolicy;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Server-only reads and online transactions. No optimistic success or offline queued batches. */
public final class AppointmentRepository {
    public enum Error { NETWORK, PERMISSION, SLOT_TAKEN, NOT_ELIGIBLE, INVALID_DATA, SESSION_CHANGED,
        MISSING_DOCUMENT, FAILED_PRECONDITION, DOCTOR_INACTIVE, DATE_BLOCKED, NO_ACTIVE_SCHEDULE,
        NO_FUTURE_SLOTS, NO_INVENTORY, NO_AVAILABLE_SLOTS, UNKNOWN }
    public static final class Failure extends RuntimeException {
        public final Error error;
        public Failure(Error error) { super(error.name()); this.error = error; }
    }
    public static final class OperationFailure extends RuntimeException {
        public final String operation, path;
        OperationFailure(String operation, String path, Exception cause) {
            super(operation, cause); this.operation = operation; this.path = path;
        }
    }
    /** Keep the real SDK code and the failed read/write location; never turn failure into empty data. */
    public static <T> Task<T> at(Task<T> task, String operation, String path) {
        return task.continueWith(result -> {
            if (!result.isSuccessful()) throw new OperationFailure(operation, path, result.getException());
            return result.getResult();
        });
    }
    public static final class Availability {
        public final Doctor doctor;
        public final List<InventorySlot> slots;
        public final Error emptyReason;
        public final int publishedCount;
        public final String path;
        Availability(Doctor doctor, List<InventorySlot> slots, int publishedCount, Error emptyReason, String path) {
            this.doctor = doctor; this.slots = Collections.unmodifiableList(slots);
            this.publishedCount = publishedCount; this.emptyReason = emptyReason; this.path = path;
        }
    }

    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    public String newId() { return db.collection("appointments").document().getId(); }
    private DocumentReference appointment(String id) {
        if (!DoctorMapper.isValidId(id)) throw new Failure(Error.INVALID_DATA);
        return db.collection("appointments").document(id);
    }
    static void requireOwner(String owner) {
        if (!DoctorRepository.sameUser(owner)) throw new Failure(Error.SESSION_CHANGED);
    }
    public CollectionReference slots(String doctor, long midnight) {
        return db.collection(inventoryPath(doctor, midnight));
    }
    public static String inventoryPath(String doctor, long midnight) {
        if (!DoctorMapper.isValidId(doctor) || midnight < 0) throw new Failure(Error.INVALID_DATA);
        return "doctorSlotInventory/" + doctor + "/dates/" + midnight + "/slots";
    }

    public Task<Availability> available(String owner, String doctorId, long midnight) {
        requireOwner(owner);
        DocumentReference doctorRef = db.collection("doctors").document(doctorId);
        return at(doctorRef.get(Source.SERVER), "Read doctor", doctorRef.getPath()).continueWithTask(doctorTask -> {
            if (!doctorTask.isSuccessful()) throw doctorTask.getException();
            requireOwner(owner); confirmed(doctorTask.getResult());
            if (!doctorTask.getResult().exists()) throw new OperationFailure("Read doctor", doctorRef.getPath(), new Failure(Error.MISSING_DOCUMENT));
            Doctor doctor = DoctorMapper.fromMap(doctorId, doctorTask.getResult().getData());
            if (!doctor.isActive()) throw new Failure(Error.DOCTOR_INACTIVE);
            return at(doctorRef.collection("schedules").whereEqualTo("active", true).get(Source.SERVER),
                    "Read active schedules", doctorRef.getPath() + "/schedules")
                    .continueWithTask(scheduleTask -> {
                        if (!scheduleTask.isSuccessful()) throw scheduleTask.getException();
                        requireOwner(owner); confirmed(scheduleTask.getResult());
                        List<DoctorSchedule> schedules = new ArrayList<>();
                        for (DocumentSnapshot doc : scheduleTask.getResult()) {
                            schedules.add(DoctorScheduleMapper.fromMap(doc.getId(), doc.getData()));
                        }
                        return at(slots(doctorId, midnight).get(Source.SERVER), "Read published slots", inventoryPath(doctorId, midnight)).continueWith(slotTask -> {
                            if (!slotTask.isSuccessful()) throw slotTask.getException();
                            requireOwner(owner); confirmed(slotTask.getResult());
                            List<InventorySlot> inventory = new ArrayList<>();
                            for (DocumentSnapshot doc : slotTask.getResult()) {
                                InventorySlot slot = InventorySlotMapper.fromMap(doctorId, Long.toString(midnight), doc.getId(), doc.getData());
                                inventory.add(slot);
                            }
                            return availabilityFrom(doctor, midnight, schedules, inventory, System.currentTimeMillis());
                        });
                    });
        }).continueWithTask(loaded -> {
            if (!loaded.isSuccessful()) throw loaded.getException();
            Availability value = loaded.getResult();
            if (value.slots.isEmpty()) return Tasks.forResult(value);
            return new SlotSerialRepository().numbers(owner, doctorId, midnight).continueWith(numbers -> {
                requireOwner(owner);
                List<InventorySlot> shown = new ArrayList<>();
                for (InventorySlot slot : value.slots) shown.add(slot.withSlotSerial(numbers.isSuccessful() ? numbers.getResult().getOrDefault(slot.id, 0) : 0));
                return new Availability(value.doctor, shown, value.publishedCount, value.emptyReason, value.path);
            });
        });
    }

    /** Pure classification used by the screen: unpublished != fully booked != wrong weekday. */
    public static Availability availabilityFrom(Doctor doctor, long midnight, List<DoctorSchedule> schedules,
                                                 List<InventorySlot> inventory, long now) {
        List<InventorySlot> available = new ArrayList<>();
        List<AppointmentSlot> intervals = new ArrayList<>();
        int eligible = 0;
        for (InventorySlot slot : inventory) {
            intervals.add(slot.interval());
            if (slot.startAt.toDate().getTime() <= now) continue;
            for (DoctorSchedule schedule : schedules) {
                if (AppointmentPolicy.fits(slot, schedule)) {
                    eligible++; if ("available".equals(slot.status)) available.add(slot); break;
                }
            }
        }
        if (com.example.medibook.utils.SlotInventory.nonOverlapping(intervals).size() != intervals.size()) throw new Failure(Error.INVALID_DATA);
        available.sort(Comparator.comparing(slot -> slot.startAt));
        Error reason = !doctor.isActive() ? Error.DOCTOR_INACTIVE
                : !AppointmentPolicy.hasScheduleOn(schedules, midnight) ? Error.NO_ACTIVE_SCHEDULE
                : inventory.isEmpty() ? Error.NO_INVENTORY
                : eligible == 0 ? Error.NO_FUTURE_SLOTS
                : available.isEmpty() ? Error.NO_AVAILABLE_SLOTS : null;
        if (reason != null) available.clear();
        return new Availability(doctor, available, inventory.size(), reason, inventoryPath(doctor.getId(), midnight));
    }

    public Task<Appointment> get(String owner, String id) {
        requireOwner(owner);
        return at(appointment(id).get(Source.SERVER), "Read appointment", appointment(id).getPath()).continueWith(task -> {
            if (!task.isSuccessful()) throw task.getException();
            requireOwner(owner); confirmed(task.getResult());
            Appointment value = AppointmentMapper.fromSnapshot(task.getResult());
            com.example.medibook.local.LocalPatientServices.appointmentBestEffort(owner, value);
            return value;
        });
    }

    public Task<List<Appointment>> list(String owner, boolean admin) {
        requireOwner(owner);
        Query query = db.collection("appointments");
        if (!admin) query = query.whereEqualTo("patientUid", owner);
        // One single-field filter, sorting in memory: no composite index needed for this lab list.
        return query.get(Source.SERVER).continueWith(task -> {
            if (!task.isSuccessful()) throw task.getException();
            requireOwner(owner); confirmed(task.getResult());
            List<Appointment> entries = new ArrayList<>();
            for (DocumentSnapshot doc : task.getResult()) entries.add(AppointmentMapper.fromSnapshot(doc));
            entries.sort(Comparator.comparing(Appointment::getStartAt).thenComparing(Appointment::getId));
            if (!admin) com.example.medibook.local.LocalPatientServices.listBestEffort(owner, entries);
            return Collections.unmodifiableList(entries);
        });
    }

    /** Keep id stable across retries/rotation/unknown responses, even if the selected slot changes. */
    public Task<Appointment> book(String owner, String id, InventorySlot selected, Doctor shownDoctor) {
        requireOwner(owner);
        DocumentReference target = appointment(id);
        // A missing appointment get is permission-denied by the own-only Rules, not necessarily 404.
        // Only a successful server read is evidence that an earlier attempt committed.
        return get(owner, id).continueWithTask(reconcile -> {
            requireOwner(owner);
            if (reconcile.isSuccessful()) {
                if (!owner.equals(reconcile.getResult().getPatientUid())) throw new Failure(Error.PERMISSION);
                com.example.medibook.cache.PatientDataCache.get().invalidateAppointments(owner);
                RealtimeDisplayRepository.publishBestEffort(owner, reconcile.getResult());
                return Tasks.forResult(reconcile.getResult());
            }
            Error prior = errorFor(reconcile.getException());
            if (prior != Error.PERMISSION && prior != Error.MISSING_DOCUMENT) throw reconcile.getException();
            DocumentReference slotRef = slots(selected.doctorId, selected.appointmentDate.toDate().getTime()).document(selected.id);
            DocumentReference doctorRef = db.collection("doctors").document(selected.doctorId);
            Task<Void> commit = db.runTransaction(transaction -> {
                requireOwner(owner);
                DocumentSnapshot slotDoc = transaction.get(slotRef);
                InventorySlot slot = InventorySlotMapper.fromMap(selected.doctorId,
                        Long.toString(selected.appointmentDate.toDate().getTime()), selected.id, slotDoc.getData());
                if (!"available".equals(slot.status)) throw new Failure(Error.SLOT_TAKEN);
                if (slot.startAt.toDate().getTime() <= System.currentTimeMillis()) throw new Failure(Error.NOT_ELIGIBLE);
                Doctor doctor = DoctorMapper.fromMap(selected.doctorId, transaction.get(doctorRef).getData());
                DoctorSchedule schedule = DoctorScheduleMapper.fromMap(slot.scheduleId,
                        transaction.get(doctorRef.collection("schedules").document(slot.scheduleId)).getData());
                DocumentSnapshot patient = transaction.get(db.collection("users").document(owner));
                if (!AppointmentPolicy.sameQuote(shownDoctor, doctor) || !AppointmentPolicy.fits(slot, schedule)) {
                    throw new Failure(Error.NOT_ELIGIBLE);
                }
                requireOwner(owner);
                Object stamp = FieldValue.serverTimestamp();
                transaction.update(slotRef, AppointmentWrites.reserve(slot, id, stamp));
                transaction.set(slotRef.collection("bookings").document("g" + (slot.generation + 1)),
                        BookingLockMapper.writeMap(new BookingLock(id, owner, slot.scheduleId,
                                slot.startAt, slot.endAt, Timestamp.now()), stamp));
                transaction.set(target, AppointmentWrites.confirmed(id, owner,
                        InventorySlotMapper.text(patient.getData(), "fullName"),
                        InventorySlotMapper.text(patient.getData(), "phone"), doctor, slot, stamp));
                return null;
            });
            return reconcileWrite(owner, id, at(commit, "Commit booking", slotRef.getPath()), false);
        });
    }

    public Task<Appointment> cancel(String owner, String id) {
        requireOwner(owner);
        return cancelAttempt(owner, id, false).continueWithTask(first -> {
            if (first.isSuccessful()) return reconcileWrite(owner, id, first, true);
            // Patients cannot read date blocks. A denied available-release is retried as blocked;
            // Rules permit this ONLY when a block exists. Neither attempt changes generation.
            if (errorFor(first.getException()) == Error.PERMISSION) {
                return reconcileWrite(owner, id, cancelAttempt(owner, id, true), true);
            }
            return reconcileWrite(owner, id, first, true);
        });
    }

    private Task<Void> cancelAttempt(String owner, String id, boolean blocked) {
        DocumentReference target = appointment(id);
        return db.runTransaction(transaction -> {
            requireOwner(owner);
            Appointment current = AppointmentMapper.fromSnapshot(transaction.get(target));
            if (current.getStatus() == AppointmentStatus.CANCELLED) return null;
            if (!AppointmentPolicy.canCancel(current, System.currentTimeMillis())) throw new Failure(Error.NOT_ELIGIBLE);
            // Do not read the slot/lock: Patients lose slot reads after Doctor deactivation, but
            // retain the right to cancel their own future appointment. Rules check the current lock.
            DocumentReference slot = slots(current.getDoctorId(), current.getAppointmentDate().toDate().getTime())
                    .document(com.example.medibook.utils.ReservationIdentity.slotId(
                            current.getStartAt().toDate().getTime(), current.getEndAt().toDate().getTime()));
            requireOwner(owner);
            transaction.update(target, AppointmentWrites.cancel(FieldValue.serverTimestamp()));
            transaction.update(slot, AppointmentWrites.release(blocked, FieldValue.serverTimestamp()));
            return null;
        });
    }

    private Task<Appointment> reconcileWrite(String owner, String id, Task<Void> write, boolean cancelling) {
        return write.continueWithTask(result -> get(owner, id).continueWithTask(read -> {
            if (read.isSuccessful() && AppointmentPolicy.reconciles(read.getResult(), cancelling)) {
                com.example.medibook.cache.PatientDataCache.get().invalidateAppointments(owner);
                RealtimeDisplayRepository.publishBestEffort(owner, read.getResult());
                return Tasks.forResult(read.getResult());
            }
            if (read.isSuccessful() && result.isSuccessful()) throw new Failure(Error.UNKNOWN);
            throw result.isSuccessful() ? read.getException() : result.getException();
        }));
    }

    private static boolean isNotFound(Exception error) {
        return error instanceof FirebaseFirestoreException
                && ((FirebaseFirestoreException) error).getCode() == FirebaseFirestoreException.Code.NOT_FOUND;
    }
    static void confirmed(DocumentSnapshot doc) {
        if (doc.getMetadata().isFromCache() || doc.getMetadata().hasPendingWrites()) throw new Failure(Error.NETWORK);
    }
    private static void confirmed(QuerySnapshot snapshot) {
        if (snapshot.getMetadata().isFromCache() || snapshot.getMetadata().hasPendingWrites()) throw new Failure(Error.NETWORK);
    }
    public static Error errorFor(Exception error) {
        FirebaseFirestoreException sdkError = null;
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
            if (cause instanceof Failure) return ((Failure) cause).error;
            if (cause instanceof DoctorScheduleRepository.Failure
                    && ((DoctorScheduleRepository.Failure) cause).error == DoctorScheduleRepository.Error.PARENT_MISSING) return Error.MISSING_DOCUMENT;
            if (cause instanceof IllegalArgumentException) return Error.INVALID_DATA;
            if (cause instanceof FirebaseFirestoreException) sdkError = (FirebaseFirestoreException) cause;
        }
        return sdkError == null ? Error.UNKNOWN : errorForCode(sdkError.getCode().name());
    }
    /** SDK Code initializes Android SparseArray; its canonical name is a JVM-testable boundary. */
    public static Error errorForCode(String code) {
        if (code == null) return Error.UNKNOWN;
        switch (code) {
            case "PERMISSION_DENIED": case "UNAUTHENTICATED": return Error.PERMISSION;
            case "UNAVAILABLE": case "DEADLINE_EXCEEDED": return Error.NETWORK;
            case "ABORTED": case "ALREADY_EXISTS": return Error.SLOT_TAKEN;
            case "NOT_FOUND": return Error.MISSING_DOCUMENT;
            case "FAILED_PRECONDITION": return Error.FAILED_PRECONDITION;
            default: return Error.UNKNOWN;
        }
    }
    public static String errorCode(Exception error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
            if (cause instanceof FirebaseFirestoreException) return ((FirebaseFirestoreException) cause).getCode().name();
        }
        return errorFor(error).name();
    }
}
