package com.example.medibook.repositories;

import android.util.Log;
import com.example.medibook.models.Appointment;
import com.example.medibook.models.InventorySlot;
import com.example.medibook.utils.ReservationIdentity;
import com.example.medibook.utils.AppointmentSerials;
import com.example.medibook.utils.SlotSerials;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Source;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable sidecars leave the 17/12/6-field reservation contract completely unchanged. */
public final class SlotSerialRepository {
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private DocumentReference date(String doctor, long date) {
        if (!DoctorMapper.isValidId(doctor) || date < 0) throw new IllegalArgumentException("Invalid inventory date");
        return db.document("doctorSlotInventory/" + doctor + "/dates/" + date);
    }

    /** Admin-only: migrate missing sidecars in chronological order; never overwrite any assignment. */
    public Task<Void> assignDate(String owner, String doctor, long midnight) {
        AppointmentRepository.requireOwner(owner);
        return date(doctor, midnight).collection("slots").get(Source.SERVER).continueWithTask(read -> {
            if (!read.isSuccessful()) throw read.getException();
            AppointmentRepository.requireOwner(owner);
            if (read.getResult().getMetadata().isFromCache() || read.getResult().getMetadata().hasPendingWrites()) throw new AppointmentRepository.Failure(AppointmentRepository.Error.NETWORK);
            List<InventorySlot> slots = new ArrayList<>();
            for (DocumentSnapshot doc : read.getResult()) slots.add(InventorySlotMapper.fromMap(doctor, Long.toString(midnight), doc.getId(), doc.getData()));
            Task<Void> chain = Tasks.forResult(null);
            for (InventorySlot slot : SlotSerials.publicationOrder(slots)) chain = chain.continueWithTask(prior -> {
                if (!prior.isSuccessful()) throw prior.getException();
                return assign(owner, doctor, midnight, slot.id);
            });
            return chain;
        });
    }

    private Task<Void> assign(String owner, String doctor, long midnight, String slotId) {
        DocumentReference counter = date(doctor, midnight);
        DocumentReference serial = counter.collection("serials").document(slotId);
        return db.runTransaction(tx -> {
            AppointmentRepository.requireOwner(owner);
            DocumentSnapshot existing = tx.get(serial);
            if (existing.exists()) { number(existing); return null; }
            DocumentSnapshot state = tx.get(counter);
            DocumentSnapshot slot = tx.get(counter.collection("slots").document(slotId));
            if (!slot.exists()) throw new IllegalArgumentException("Missing inventory slot");
            long last = state.exists() ? InventorySlotMapper.number(state.getData(), "lastSerial") : 0;
            int next = SlotSerials.next(last);
            Object stamp = FieldValue.serverTimestamp();
            Map<String, Object> mapping = new HashMap<>(); mapping.put("number", next); mapping.put("createdAt", stamp);
            Map<String, Object> cursor = new HashMap<>(); cursor.put("lastSerial", next); cursor.put("lastSlotId", slotId); cursor.put("updatedAt", stamp);
            AppointmentRepository.requireOwner(owner);
            tx.set(serial, mapping); tx.set(counter, cursor); return null;
        });
    }

    private static int number(DocumentSnapshot doc) {
        long value = InventorySlotMapper.number(doc.getData(), "number");
        if (value < 1 || value > SlotSerials.MAX) throw new IllegalArgumentException("Invalid slot serial");
        return (int) value;
    }

    /** Presentation lookup only. Missing/undeployed/unavailable sidecars stay unnumbered. */
    public Task<Map<String, Integer>> numbers(String owner, String doctor, long midnight) {
        AppointmentRepository.requireOwner(owner);
        return date(doctor, midnight).collection("serials").get(Source.SERVER).continueWith(task -> {
            AppointmentRepository.requireOwner(owner);
            if (!task.isSuccessful() || task.getResult().getMetadata().isFromCache() || task.getResult().getMetadata().hasPendingWrites()) {
                Log.w("MediBookSlotSerial", "Serial lookup unavailable; leaving slot number unresolved"); return Collections.emptyMap();
            }
            Map<String, Integer> result = new HashMap<>();
            for (DocumentSnapshot doc : task.getResult()) {
                try { result.put(doc.getId(), number(doc)); }
                catch (IllegalArgumentException invalid) { Log.w("MediBookSlotSerial", "Invalid serial skipped"); }
            }
            return result;
        });
    }

    public Task<List<Appointment>> appointments(String owner, List<Appointment> values) {
        Map<String, Task<Map<String, Integer>>> groups = new HashMap<>();
        for (Appointment a : values) {
            String key = a.getDoctorId() + "/" + a.getAppointmentDate().toDate().getTime();
            if (!groups.containsKey(key)) groups.put(key, numbers(owner, a.getDoctorId(), a.getAppointmentDate().toDate().getTime()));
        }
        return Tasks.whenAllComplete(new ArrayList<>(groups.values())).continueWith(task -> {
            AppointmentRepository.requireOwner(owner);
            List<Appointment> result = new ArrayList<>();
            for (Appointment a : values) {
                Task<Map<String, Integer>> read = groups.get(a.getDoctorId() + "/" + a.getAppointmentDate().toDate().getTime());
                int serial = read.isSuccessful() ? read.getResult().getOrDefault(ReservationIdentity.slotId(a.getStartAt().toDate().getTime(), a.getEndAt().toDate().getTime()), 0) : 0;
                result.add(AppointmentSerials.retain(a.withSlotSerial(serial), a));
            }
            return result;
        });
    }
}
