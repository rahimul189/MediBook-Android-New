package com.example.medibook.utils;

import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentStatus;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import com.example.medibook.repositories.AppointmentMapper;

/** Pure selection: neither RTDB pointers nor a last-booked record can establish eligibility. */
public final class NextAppointment {
    private NextAppointment() { }
    public static boolean eligible(String owner, Appointment value, long now) {
        return owner != null && value != null && owner.equals(value.getPatientUid())
                && value.getStatus() == AppointmentStatus.CONFIRMED
                && value.getStartAt().toDate().getTime() > now
                && value.getEndAt().toDate().getTime() > value.getStartAt().toDate().getTime();
    }
    public static Appointment select(String owner, List<Appointment> values, long now) {
        Appointment next = null;
        if (values != null) for (Appointment value : values) {
            if (!eligible(owner, value, now)) continue;
            if (next == null || value.getStartAt().compareTo(next.getStartAt()) < 0
                    || (value.getStartAt().equals(next.getStartAt()) && value.getId().compareTo(next.getId()) < 0)) next = value;
        }
        return next;
    }

    /** Only optional presentation fields may be repaired; ownership, times, status and IDs never are. */
    public static Appointment from(String id, Map<String, Object> raw) {
        if (raw == null) throw new IllegalArgumentException("Missing appointment");
        Map<String, Object> values = new HashMap<>(raw);
        Object name = values.get("doctorName"), specialty = values.get("doctorSpecialization");
        if (!(name instanceof String) || ((String) name).trim().isEmpty()) values.put("doctorName", "Doctor unavailable");
        if (!(specialty instanceof String) || ((String) specialty).trim().isEmpty()) values.put("doctorSpecialization", "Specialization unavailable");
        return AppointmentMapper.fromMap(id, values);
    }
}
