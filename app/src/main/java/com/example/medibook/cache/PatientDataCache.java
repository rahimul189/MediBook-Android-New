package com.example.medibook.cache;

import com.example.medibook.models.Appointment;
import com.example.medibook.models.Doctor;
import com.example.medibook.models.DoctorSchedule;
import com.example.medibook.utils.AppointmentSerials;
import com.example.medibook.utils.SlotSerials;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Process-wide, bounded, owner-scoped cache for data Firestore already returned from the server.
 *
 * <p>This is a navigation aid only. It is never authorization: booking still runs through the
 * existing server transaction, and Admin access still requires a freshly verified role. Data is
 * held in memory, tied to one account, cleared on logout/account switch, and invalidated whenever
 * a doctor or appointment write is acknowledged by Firestore.</p>
 */
public final class PatientDataCache {
    private static final int MAX_PROFILES = 24;
    private static final int MAX_SCHEDULES = 24;
    private static final int MAX_DIRECTORY = 500;
    private static final int MAX_APPOINTMENT_SERIALS = 128;
    private static final int MAX_DETAILS = 24;
    private static final PatientDataCache INSTANCE = new PatientDataCache();

    public static PatientDataCache get() { return INSTANCE; }

    private String owner;
    private List<Doctor> directory;
    private long directoryLoadedAt;
    private final Map<String, Doctor> profiles = new LinkedHashMap<>();
    private final Map<String, List<DoctorSchedule>> schedules = new LinkedHashMap<>();
    private List<Appointment> appointments;
    private final Map<String, Appointment> numberedAppointments = new LinkedHashMap<>();
    private final Map<String, Appointment> details = new LinkedHashMap<>();
    private long appointmentsLoadedAt;

    /** Public for isolated unit tests; production reads {@link #get()}. */
    public PatientDataCache() { }

    /** Binds the cache to one account. Another UID always clears everything first. */
    public synchronized void bindOwner(String uid) {
        if (uid == null || uid.isEmpty()) { clear(); return; }
        if (!uid.equals(owner)) { clear(); owner = uid; }
    }

    public synchronized void clear() {
        owner = null;
        directory = null;
        directoryLoadedAt = 0;
        profiles.clear();
        schedules.clear();
        appointments = null;
        numberedAppointments.clear();
        details.clear();
        appointmentsLoadedAt = 0;
    }

    public synchronized List<Doctor> directory(String uid) {
        return owns(uid) && directory != null ? new ArrayList<>(directory) : null;
    }

    public synchronized long directoryLoadedAt(String uid) {
        return owns(uid) && directory != null ? directoryLoadedAt : 0;
    }

    public synchronized void putDirectory(String uid, List<Doctor> doctors) {
        if (!owns(uid)) return;
        if (doctors == null || doctors.size() > MAX_DIRECTORY) { directory = null; return; }
        directory = new ArrayList<>(doctors);
        // Bring already-open profiles in line with the newer active-directory snapshot.
        for (String id : new ArrayList<>(profiles.keySet())) {
            Doctor current = null;
            for (Doctor value : doctors) if (id.equals(value.getId())) { current = value; break; }
            if (current == null) profiles.remove(id); else profiles.put(id, current);
        }
        directoryLoadedAt = System.currentTimeMillis();
    }

    public synchronized Doctor doctor(String uid, String doctorId) {
        if (!owns(uid) || doctorId == null) return null;
        Doctor profile = profiles.get(doctorId);
        if (profile != null) return profile;
        // The directory already contains a server-verified profile, not an untrusted Intent model.
        if (directory != null) for (Doctor doctor : directory) if (doctorId.equals(doctor.getId())) return doctor;
        return null;
    }

    public synchronized void putDoctor(String uid, Doctor doctor) {
        if (!owns(uid) || doctor == null || doctor.getId().isEmpty()) return;
        profiles.remove(doctor.getId());
        profiles.put(doctor.getId(), doctor);
        trim(profiles, MAX_PROFILES);
    }

    public synchronized List<DoctorSchedule> schedules(String uid, String doctorId) {
        if (!owns(uid) || doctorId == null) return null;
        List<DoctorSchedule> value = schedules.get(doctorId);
        return value == null ? null : new ArrayList<>(value);
    }

    public synchronized void putSchedules(String uid, String doctorId, List<DoctorSchedule> value) {
        if (!owns(uid) || doctorId == null) return;
        schedules.remove(doctorId);
        schedules.put(doctorId, value == null ? Collections.<DoctorSchedule>emptyList() : new ArrayList<>(value));
        trim(schedules, MAX_SCHEDULES);
    }

    public synchronized List<Appointment> appointments(String uid) {
        return owns(uid) && appointments != null ? new ArrayList<>(appointments) : null;
    }

    public synchronized long appointmentsLoadedAt(String uid) {
        return owns(uid) && appointments != null ? appointmentsLoadedAt : 0;
    }

    public synchronized void putAppointments(String uid, List<Appointment> value) {
        if (!owns(uid) || value == null) return;
        appointments = new ArrayList<>(value);
        for (Appointment appointment : value) {
            rememberAppointmentSerial(uid, appointment);
            if (details.containsKey(appointment.getId())) putAppointment(uid, appointment);
        }
        appointmentsLoadedAt = System.currentTimeMillis();
    }

    /** Cached Patient display only. Admin/other-patient records are never reused as an own detail. */
    public synchronized Appointment appointment(String uid, String id) {
        if (!owns(uid) || id == null) return null;
        Appointment value = details.get(id);
        if (value == null && appointments != null) {
            for (Appointment entry : appointments) if (id.equals(entry.getId())) { value = entry; break; }
        }
        return value != null && uid.equals(value.getPatientUid()) ? withKnownAppointmentSerial(uid, value) : null;
    }

    public synchronized void putAppointment(String uid, Appointment value) {
        if (!owns(uid) || value == null || !uid.equals(value.getPatientUid())) return;
        details.remove(value.getId()); details.put(value.getId(), withKnownAppointmentSerial(uid, value));
        trim(details, MAX_DETAILS);
    }

    /** Only a previously resolved number is reused; all other fields stay from the new snapshot. */
    public synchronized Appointment withKnownAppointmentSerial(String uid, Appointment value) {
        return owns(uid) && value != null
                ? AppointmentSerials.retain(value, numberedAppointments.get(value.getId())) : value;
    }

    /** Call only after the screen's owner/request checks accept a sidecar result. Never persisted. */
    public synchronized void rememberAppointmentSerial(String uid, Appointment value) {
        if (!owns(uid) || value == null || !SlotSerials.isAssigned(value.getSlotSerial())) return;
        numberedAppointments.remove(value.getId());
        numberedAppointments.put(value.getId(), value);
        trim(numberedAppointments, MAX_APPOINTMENT_SERIALS);
    }

    /** Doctor add/edit/activation changes make the directory and every profile stale. */
    public synchronized void invalidateDoctors() {
        directory = null;
        directoryLoadedAt = 0;
        profiles.clear();
        schedules.clear();
    }

    /** Booking/cancellation and schedule publication change appointment summaries. */
    public synchronized void invalidateAppointments() {
        DashboardCache.get().invalidateAppointments(owner);
        appointments = null;
        numberedAppointments.clear();
        details.clear();
        appointmentsLoadedAt = 0;
    }

    public synchronized void invalidateAppointments(String uid) {
        if (owns(uid)) invalidateAppointments();
    }

    /** Account switching must never show the previous patient's private rows. */
    public synchronized void invalidateUserData() {
        DashboardCache.get().invalidateAppointments(owner);
        appointments = null;
        numberedAppointments.clear();
        details.clear();
        appointmentsLoadedAt = 0;
        if (owner != null) { directory = null; directoryLoadedAt = 0; profiles.clear(); schedules.clear(); }
    }

    private boolean owns(String uid) { return uid != null && uid.equals(owner); }

    private static <T> void trim(Map<String, T> map, int maximum) {
        while (map.size() > maximum) {
            String eldest = map.keySet().iterator().next();
            map.remove(eldest);
        }
    }
}
