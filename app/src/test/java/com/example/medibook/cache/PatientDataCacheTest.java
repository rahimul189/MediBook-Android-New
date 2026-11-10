package com.example.medibook.cache;

import com.example.medibook.models.Doctor;
import com.example.medibook.models.DoctorSchedule;
import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentStatus;
import com.google.firebase.Timestamp;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class PatientDataCacheTest {
    private static Doctor doctor(String id) {
        return new Doctor(id, "Dr. " + id, "Medicine", "MBBS", BigDecimal.valueOf(500), "", true);
    }

    @Test public void directoryIsScopedToTheBoundAccount() {
        PatientDataCache cache = new PatientDataCache();
        cache.bindOwner("patient-a");
        cache.putDirectory("patient-a", Arrays.asList(doctor("1"), doctor("2")));
        assertEquals(2, cache.directory("patient-a").size());
        assertNull(cache.directory("patient-b"));
        cache.bindOwner("patient-b");
        assertNull(cache.directory("patient-a"));
        assertNull(cache.directory("patient-b"));
    }

    @Test public void directoryDoctorCanRenderProfileWithoutASecondServerRoundTrip() {
        PatientDataCache cache = new PatientDataCache(); cache.bindOwner("a");
        Doctor first = doctor("1"); cache.putDirectory("a", Collections.singletonList(first));
        assertSame(first, cache.doctor("a", "1")); assertNull(cache.doctor("b", "1"));
    }

    @Test public void newerDirectoryRefreshesOrEvictsAnOlderCachedProfile() {
        PatientDataCache cache = new PatientDataCache(); cache.bindOwner("a");
        cache.putDoctor("a", doctor("1"));
        cache.putDirectory("a", Collections.emptyList()); assertNull(cache.doctor("a", "1"));
        cache.putDirectory("a", Collections.singletonList(doctor("1")));
        assertNotNull(cache.doctor("a", "1"));
    }

    private static Appointment appointment(String owner) {
        return new Appointment("booking", owner, "Patient", "01712345678", "doctor", "Doctor", "Medicine", 100,
                "schedule", 1, new Timestamp(100, 0), 540, 560, new Timestamp(1000, 0), new Timestamp(2000, 0),
                AppointmentStatus.CONFIRMED, new Timestamp(1, 0), new Timestamp(2, 0));
    }

    @Test public void detailsReuseTheOwnListButNeverAnotherPatientsOrAccountsModel() {
        PatientDataCache cache = new PatientDataCache(); cache.bindOwner("a");
        cache.putAppointments("a", Collections.singletonList(appointment("a")));
        assertNotNull(cache.appointment("a", "booking")); assertNull(cache.appointment("b", "booking"));
        cache.putAppointment("a", appointment("b"));
        assertEquals("a", cache.appointment("a", "booking").getPatientUid());
        cache.clear(); cache.bindOwner("a");
        cache.putAppointments("a", Collections.singletonList(appointment("b"))); assertNull(cache.appointment("a", "booking"));
    }

    @Test public void detailReadCacheIsClearedByWriteInvalidationAndAccountSwitch() {
        PatientDataCache cache = new PatientDataCache(); cache.bindOwner("a"); cache.putAppointment("a", appointment("a"));
        assertNotNull(cache.appointment("a", "booking")); cache.invalidateAppointments("a"); assertNull(cache.appointment("a", "booking"));
        cache.putAppointment("a", appointment("a")); cache.bindOwner("b"); cache.bindOwner("a");
        assertNull(cache.appointment("a", "booking"));
    }

    @Test public void anotherOwnerCannotWriteIntoTheCurrentCache() {
        PatientDataCache cache = new PatientDataCache();
        cache.bindOwner("a");
        cache.putDirectory("b", Collections.singletonList(doctor("x")));
        cache.putDoctor("b", doctor("x"));
        assertNull(cache.directory("a"));
        assertNull(cache.doctor("b", "x"));
    }

    @Test public void doctorInvalidationDropsDirectoryProfileAndSchedules() {
        PatientDataCache cache = new PatientDataCache();
        cache.bindOwner("a");
        cache.putDirectory("a", Collections.singletonList(doctor("1")));
        cache.putDoctor("a", doctor("1"));
        cache.putSchedules("a", "1", Collections.<DoctorSchedule>emptyList());
        cache.invalidateDoctors();
        assertNull(cache.directory("a"));
        assertNull(cache.doctor("a", "1"));
        assertNull(cache.schedules("a", "1"));
    }

    @Test public void appointmentInvalidationDropsOnlyAppointmentSummaries() {
        PatientDataCache cache = new PatientDataCache();
        cache.bindOwner("a");
        cache.putDirectory("a", Collections.singletonList(doctor("1")));
        cache.putAppointments("a", Collections.emptyList());
        assertNotNull(cache.appointments("a"));
        cache.invalidateAppointments("a");
        assertNull(cache.appointments("a"));
        assertNotNull(cache.directory("a"));
        cache.putAppointments("a", Collections.emptyList());
        cache.invalidateAppointments("other");
        assertNotNull(cache.appointments("a"));
    }

    @Test public void logoutClearsEverything() {
        PatientDataCache cache = new PatientDataCache();
        cache.bindOwner("a");
        cache.putDirectory("a", Collections.singletonList(doctor("1")));
        cache.putAppointments("a", Collections.emptyList());
        cache.clear();
        assertNull(cache.directory("a"));
        assertNull(cache.appointments("a"));
        cache.putDirectory("a", Collections.singletonList(doctor("1")));
        assertNull(cache.directory("a"));
    }

    @Test public void profileCacheIsBoundedWithoutBreakingTheLatestEntry() {
        PatientDataCache cache = new PatientDataCache();
        cache.bindOwner("a");
        for (int i = 0; i < 40; i++) cache.putDoctor("a", doctor(Integer.toString(i)));
        assertNotNull(cache.doctor("a", "39"));
        assertNull(cache.doctor("a", "0"));
    }

    @Test public void returnedListsAreSnapshotsNotLiveCacheAliases() {
        PatientDataCache cache = new PatientDataCache();
        cache.bindOwner("a");
        cache.putDirectory("a", Collections.singletonList(doctor("1")));
        cache.directory("a").clear();
        assertEquals(1, cache.directory("a").size());
    }
}
