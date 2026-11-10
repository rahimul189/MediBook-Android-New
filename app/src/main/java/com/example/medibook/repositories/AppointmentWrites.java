package com.example.medibook.repositories;

import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentStatus;
import com.example.medibook.models.Doctor;
import com.example.medibook.models.InventorySlot;
import com.example.medibook.utils.AppointmentValidation;
import com.google.firebase.Timestamp;

import java.util.HashMap;
import java.util.Map;

/** Exact write masks: no editable IDs, persisted completion, or rewritten inventory definition. */
public final class AppointmentWrites {
    private AppointmentWrites() { }

    public static Map<String, Object> confirmed(String id, String uid, String name, String phone,
                                               Doctor doctor, InventorySlot slot, Object serverTime) {
        Timestamp now = Timestamp.now();
        long fee = doctor.getConsultationFee().movePointRight(2).longValueExact();
        Appointment draft = new Appointment(id, uid, name, phone, doctor.getId(), doctor.getName(),
                doctor.getSpecialization(), fee, slot.scheduleId, slot.dayOfWeek,
                slot.appointmentDate, slot.startMinute, slot.endMinute, slot.startAt, slot.endAt,
                AppointmentStatus.CONFIRMED, now, now);
        if (!AppointmentValidation.isValid(draft) || !doctor.getId().equals(slot.doctorId)) {
            throw new IllegalArgumentException("Invalid appointment snapshot");
        }
        Map<String, Object> map = new HashMap<>();
        map.put("patientUid", uid); map.put("patientName", name); map.put("patientPhone", phone);
        map.put("doctorId", doctor.getId()); map.put("doctorName", doctor.getName());
        map.put("doctorSpecialization", doctor.getSpecialization()); map.put("consultationFee", fee);
        map.put("scheduleId", slot.scheduleId); map.put("dayOfWeek", slot.dayOfWeek);
        map.put("appointmentDate", slot.appointmentDate); map.put("startMinute", slot.startMinute); map.put("endMinute", slot.endMinute);
        map.put("startAt", slot.startAt); map.put("endAt", slot.endAt); map.put("status", "Confirmed");
        map.put("createdAt", serverTime); map.put("updatedAt", serverTime); return map;
    }

    public static Map<String, Object> reserve(InventorySlot slot, String id, Object serverTime) {
        if (!"available".equals(slot.status) || slot.generation >= 9_999_999_999L || !DoctorMapper.isValidId(id)) {
            throw new IllegalArgumentException("Slot is not available");
        }
        Map<String, Object> map = new HashMap<>();
        map.put("status", "booked"); map.put("generation", slot.generation + 1);
        map.put("appointmentId", id); map.put("updatedAt", serverTime); return map;
    }

    public static Map<String, Object> cancel(Object serverTime) {
        Map<String, Object> map = new HashMap<>(); map.put("status", "Cancelled"); map.put("updatedAt", serverTime); return map;
    }

    public static Map<String, Object> release(boolean blocked, Object serverTime) {
        Map<String, Object> map = new HashMap<>(); map.put("status", blocked ? "blocked" : "available");
        map.put("appointmentId", null); map.put("updatedAt", serverTime); return map;
    }
}
