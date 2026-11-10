package com.example.medibook.repositories;

import com.example.medibook.models.AppointmentSlot;
import com.example.medibook.models.DoctorSchedule;
import com.example.medibook.utils.DhakaDateTime;
import com.example.medibook.utils.ReservationIdentity;
import com.example.medibook.utils.SlotInventory;
import com.example.medibook.utils.AppointmentPolicy;
import com.example.medibook.utils.AppointmentSlots;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Trusted single-publisher workflow; NEVER updates, deletes or reopens an existing slot. */
public final class SlotInventoryPublisher {
    private static final Set<String> BUSY = new HashSet<>();
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    public Task<Integer> publish(String owner, String doctorId, int year, int month, int day) {
        AppointmentRepository.requireOwner(owner);
        if (!DoctorMapper.isValidId(doctorId) || !DhakaDateTime.isDateBookable(year, month, day, System.currentTimeMillis())) {
            return Tasks.forException(new IllegalArgumentException("Invalid doctor or past publication date"));
        }
        synchronized (BUSY) {
            if (!BUSY.add(doctorId)) return Tasks.forException(new AppointmentRepository.Failure(AppointmentRepository.Error.NOT_ELIGIBLE));
        }
        Task<Integer> task;
        try { task = publishDate(owner, doctorId, year, month, day); }
        catch (RuntimeException failure) { task = Tasks.forException(failure); }
        task = AppointmentRepository.at(task, "Publish slots", AppointmentRepository.inventoryPath(doctorId,
                DhakaDateTime.localDateAtMidnight(year, month, day)));
        task.addOnCompleteListener(done -> { synchronized (BUSY) { BUSY.remove(doctorId); } });
        return task;
    }

    private Task<Integer> publishDate(String owner, String doctorId, int year, int month, int day) {
        if (!DhakaDateTime.isDateBookable(year, month, day, System.currentTimeMillis())) {
            return Tasks.forException(new IllegalArgumentException("Past booking date"));
        }
        long midnight = DhakaDateTime.localDateAtMidnight(year, month, day);
        int weekday = DhakaDateTime.isoDayOfWeek(year, month, day);
        AppointmentRepository appointments = new AppointmentRepository();
        com.google.firebase.firestore.DocumentReference block = db.collection("doctorDateBlocks").document(doctorId)
                .collection("dates").document(Long.toString(midnight));
        return AppointmentRepository.at(block.get(Source.SERVER), "Read date block", block.getPath()).continueWithTask(blockTask -> {
            if (!blockTask.isSuccessful()) throw blockTask.getException();
            AppointmentRepository.requireOwner(owner); AppointmentRepository.confirmed(blockTask.getResult());
            if (blockTask.getResult().exists()) throw new AppointmentRepository.Failure(AppointmentRepository.Error.DATE_BLOCKED);
            return AppointmentRepository.at(new DoctorScheduleRepository().load(owner, doctorId),
                    "Read doctor and weekly schedules", "doctors/" + doctorId + "/schedules");
        }).continueWithTask(loaded -> {
            if (!loaded.isSuccessful()) throw loaded.getException();
            AppointmentRepository.requireOwner(owner);
            if (!loaded.getResult().doctor.isActive()) throw new AppointmentRepository.Failure(AppointmentRepository.Error.DOCTOR_INACTIVE);
            List<AppointmentSlot> candidates = candidates(loaded.getResult().schedules, year, month, day, System.currentTimeMillis());
            return AppointmentRepository.at(appointments.slots(doctorId, midnight).get(Source.SERVER),
                    "Read existing inventory", AppointmentRepository.inventoryPath(doctorId, midnight)).continueWithTask(existingTask -> {
                if (!existingTask.isSuccessful()) throw existingTask.getException();
                AppointmentRepository.requireOwner(owner);
                if (existingTask.getResult().getMetadata().isFromCache() || existingTask.getResult().getMetadata().hasPendingWrites()) {
                    throw new AppointmentRepository.Failure(AppointmentRepository.Error.NETWORK);
                }
                List<AppointmentSlot> reserved = new ArrayList<>();
                for (DocumentSnapshot doc : existingTask.getResult()) {
                    reserved.add(InventorySlotMapper.fromMap(doctorId, Long.toString(midnight), doc.getId(), doc.getData()).interval());
                }
                if (SlotInventory.nonOverlapping(reserved).size() != reserved.size()) {
                    throw new IllegalArgumentException("Existing inventory overlaps; publication stopped");
                }
                List<AppointmentSlot> planned = SlotInventory.nonOverlapping(candidates, reserved);
                Task<Integer> chain = Tasks.forResult(0);
                for (AppointmentSlot candidate : planned) {
                    chain = chain.continueWithTask(previous -> {
                        if (!previous.isSuccessful()) throw previous.getException();
                        int count = previous.getResult();
                        // One create per transaction, well below the Rules access/expression budgets.
                        return db.runTransaction(transaction -> {
                            AppointmentRepository.requireOwner(owner);
                            com.google.firebase.firestore.DocumentReference target = appointments.slots(doctorId, midnight)
                                    .document(ReservationIdentity.slotId(candidate));
                            DocumentSnapshot current = transaction.get(target);
                            if (current.exists()) return count; // retry/another writer: never overwrite.
                            if (transaction.get(block).exists()) throw new AppointmentRepository.Failure(AppointmentRepository.Error.DATE_BLOCKED);
                            DocumentSnapshot doctor = transaction.get(db.collection("doctors").document(doctorId));
                            if (!DoctorMapper.fromMap(doctorId, doctor.getData()).isActive()) {
                                throw new AppointmentRepository.Failure(AppointmentRepository.Error.DOCTOR_INACTIVE);
                            }
                            DoctorSchedule schedule = DoctorScheduleMapper.fromMap(candidate.getScheduleId(), transaction.get(
                                    db.collection("doctors").document(doctorId).collection("schedules").document(candidate.getScheduleId())).getData());
                            // Existing interval definitions are immutable and undeletable. Reserve
                            // every interval from the server query; do not reread all siblings for
                            // each create (quadratic reads). Cross-device new IDs remain a trust boundary.
                            if (!schedule.isActive() || schedule.getDayOfWeek() != weekday
                                    || candidate.getStartMinute() < schedule.getStartMinute()
                                    || candidate.getEndMinute() > schedule.getEndMinute()
                                    || candidate.getEndMinute() - candidate.getStartMinute() != schedule.getSlotMinutes()
                                    || (candidate.getStartMinute() - schedule.getStartMinute()) % schedule.getSlotMinutes() != 0
                                    || candidate.getStartAtMillis() <= System.currentTimeMillis()) {
                                throw new AppointmentRepository.Failure(AppointmentRepository.Error.NOT_ELIGIBLE);
                            }
                            AppointmentRepository.requireOwner(owner);
                            transaction.set(target, InventorySlotMapper.publishMap(candidate, midnight, weekday, FieldValue.serverTimestamp()));
                            return count + 1;
                        });
                    });
                }
                // Separate display-only sidecars; reservation/slot schemas and writes stay intact.
                // A partial failure is reported, and retrying this date never reassigns a number.
                return chain.continueWithTask(published -> {
                    if (!published.isSuccessful()) throw published.getException();
                    return new SlotSerialRepository().assignDate(owner, doctorId, midnight).continueWith(numbered -> {
                        if (!numbered.isSuccessful()) throw numbered.getException();
                        return published.getResult();
                    });
                });
            });
        });
    }

    /** Wrong weekday/elapsed day is a setup error, not a successful zero-slot publication. */
    public static List<AppointmentSlot> candidates(List<DoctorSchedule> schedules, int year, int month, int day, long now) {
        long midnight = DhakaDateTime.localDateAtMidnight(year, month, day);
        if (!AppointmentPolicy.hasScheduleOn(schedules, midnight)) {
            throw new AppointmentRepository.Failure(AppointmentRepository.Error.NO_ACTIVE_SCHEDULE);
        }
        List<AppointmentSlot> candidates = AppointmentSlots.generate(schedules, year, month, day, now);
        if (candidates.isEmpty()) throw new AppointmentRepository.Failure(AppointmentRepository.Error.NO_FUTURE_SLOTS);
        return candidates;
    }
}
