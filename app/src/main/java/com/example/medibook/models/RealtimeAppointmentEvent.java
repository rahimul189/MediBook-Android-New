package com.example.medibook.models;

import com.example.medibook.utils.AppointmentPolicy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Untrusted display hint, NOT a booking. Always compare with a server Firestore appointment. */
public final class RealtimeAppointmentEvent {
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList(
            "doctorId", "dateKey", "queueToken", "status", "changedAt"));
    public final String id, doctorId, dateKey, queueToken, status;
    public final long changedAt;

    private RealtimeAppointmentEvent(String id, String doctorId, String dateKey, String token, String status, long changedAt) {
        this.id = id; this.doctorId = doctorId; this.dateKey = dateKey;
        this.queueToken = token; this.status = status; this.changedAt = changedAt;
    }
    public static boolean validKey(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,128}"); }
    public static boolean validDateKey(String value) { return value != null && value.matches("[0-9]{1,16}"); }
    public static boolean validToken(String value) {
        if (value == null || !value.matches("Q[0-9]{4}")) return false;
        int minute = Integer.parseInt(value.substring(1));
        return minute >= 1 && minute <= 1440;
    }
    public static boolean validStatus(Object value) { return "Confirmed".equals(value) || "Cancelled".equals(value); }

    public static RealtimeAppointmentEvent fromAppointment(Appointment a, boolean booking) {
        return new RealtimeAppointmentEvent(a.getId(), a.getDoctorId(), Long.toString(a.getAppointmentDate().toDate().getTime()),
                AppointmentPolicy.queueToken(a.getStartMinute()), booking ? "Confirmed" : a.getStatus().getWireValue(),
                (booking ? a.getCreatedAt() : a.getUpdatedAt()).toDate().getTime());
    }
    public static RealtimeAppointmentEvent fromMap(String id, Object raw) {
        if (!validKey(id) || !(raw instanceof Map)) throw new IllegalArgumentException("Invalid display event");
        Map<?, ?> data = (Map<?, ?>) raw;
        if (!data.keySet().equals(FIELDS) || !(data.get("doctorId") instanceof String)
                || !validKey((String) data.get("doctorId")) || !(data.get("dateKey") instanceof String)
                || !validDateKey((String) data.get("dateKey")) || !(data.get("queueToken") instanceof String)
                || !validToken((String) data.get("queueToken")) || !validStatus(data.get("status"))
                || !(data.get("changedAt") instanceof Long) || (Long) data.get("changedAt") <= 0) {
            throw new IllegalArgumentException("Invalid display event");
        }
        return new RealtimeAppointmentEvent(id, (String) data.get("doctorId"), (String) data.get("dateKey"),
                (String) data.get("queueToken"), (String) data.get("status"), (Long) data.get("changedAt"));
    }
    public Map<String, Object> toMap() {
        Map<String, Object> map = queueMap();
        map.put("doctorId", doctorId); map.put("dateKey", dateKey); map.put("queueToken", queueToken);
        return map;
    }
    public Map<String, Object> queueMap() {
        Map<String, Object> map = new HashMap<>(); map.put("status", status); map.put("changedAt", changedAt); return map;
    }
    /** Idempotent/monotonic display updates. Cancellation wins a millisecond-resolution tie. */
    public static boolean shouldReplace(Object current, Map<String, Object> next) {
        if (!(current instanceof Map)) return true;
        Map<?, ?> old = (Map<?, ?>) current;
        Object previous = old.get("changedAt");
        if (!(previous instanceof Long)) return true;
        long incoming = (Long) next.get("changedAt");
        if ((Long) previous != incoming) return (Long) previous < incoming;
        return "Confirmed".equals(old.get("status")) && "Cancelled".equals(next.get("status"));
    }
    public String fingerprint() { return id + ":" + doctorId + ":" + dateKey + ":" + queueToken + ":" + status + ":" + changedAt; }

    public boolean matches(Appointment a, String viewer, boolean adminBooking) {
        RealtimeAppointmentEvent expected = fromAppointment(a, adminBooking);
        return (adminBooking || a.getPatientUid().equals(viewer))
                && a.getStatus().getWireValue().equals(status) && fingerprint().equals(expected.fingerprint());
    }
}
