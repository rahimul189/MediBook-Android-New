package com.example.medibook.models;

import com.google.firebase.Timestamp;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Immutable model fixture; no Android, Firestore network, or repository dependency. */
public class AppointmentTest {
    private static final Timestamp DATE = new Timestamp(1_793_988_000L, 0);
    private static final Timestamp START = new Timestamp(1_794_020_400L, 0);
    private static final Timestamp END = new Timestamp(1_794_022_200L, 0);
    private static final Timestamp CREATED = new Timestamp(100, 0);
    private static final Timestamp UPDATED = new Timestamp(200, 0);

    private Appointment appointment() {
        return new Appointment("appointment-1", "patient-1", "Patient Fixture",
                "+8801712345678", "doctor-1", "Doctor Fixture", "Cardiology", 50025L,
                "schedule-1", 6, DATE, 540, 570, START, END,
                AppointmentStatus.CONFIRMED, CREATED, UPDATED);
    }

    @Test
    public void retainsEveryStoredFieldAndTheSeparateDocumentId() {
        Appointment appointment = appointment();
        assertEquals("appointment-1", appointment.getId());
        assertEquals("patient-1", appointment.getPatientUid());
        assertEquals("Patient Fixture", appointment.getPatientName());
        assertEquals("+8801712345678", appointment.getPatientPhone());
        assertEquals("doctor-1", appointment.getDoctorId());
        assertEquals("Doctor Fixture", appointment.getDoctorName());
        assertEquals("Cardiology", appointment.getDoctorSpecialization());
        assertEquals(50025L, appointment.getConsultationFee());
        assertEquals("schedule-1", appointment.getScheduleId());
        assertEquals(6, appointment.getDayOfWeek());
        assertSame(DATE, appointment.getAppointmentDate());
        assertEquals(540, appointment.getStartMinute());
        assertEquals(570, appointment.getEndMinute());
        assertSame(START, appointment.getStartAt());
        assertSame(END, appointment.getEndAt());
        assertEquals(AppointmentStatus.CONFIRMED, appointment.getStatus());
        assertSame(CREATED, appointment.getCreatedAt());
        assertSame(UPDATED, appointment.getUpdatedAt());
    }

    @Test
    public void everyStatusCanBeHeldWithoutChangingOtherFields() {
        for (AppointmentStatus status : AppointmentStatus.values()) {
            Appointment appointment = new Appointment("appointment-1", "patient-1",
                    "Patient Fixture", "+8801712345678", "doctor-1", "Doctor Fixture",
                    "Cardiology", 50025L, "schedule-1", 6, DATE, 540, 570, START, END,
                    status, CREATED, UPDATED);
            assertEquals(status, appointment.getStatus());
            assertEquals(50025L, appointment.getConsultationFee());
        }
    }

    @Test
    public void classAndAllInstanceFieldsAreFinal() {
        assertTrue(Modifier.isFinal(Appointment.class.getModifiers()));
        for (Field field : Appointment.class.getDeclaredFields()) {
            assertTrue(field.getName(), Modifier.isFinal(field.getModifiers()));
            assertTrue(field.getName(), Modifier.isPrivate(field.getModifiers()));
        }
    }

    @Test
    public void referenceFieldsCannotBeNull() {
        assertThrows(NullPointerException.class, () -> new Appointment(null, "patient-1",
                "Patient Fixture", "+8801712345678", "doctor-1", "Doctor Fixture",
                "Cardiology", 50025L, "schedule-1", 6, DATE, 540, 570, START, END,
                AppointmentStatus.CONFIRMED, CREATED, UPDATED));
        assertThrows(NullPointerException.class, () -> new Appointment("appointment-1",
                "patient-1", "Patient Fixture", "+8801712345678", "doctor-1", "Doctor Fixture",
                "Cardiology", 50025L, "schedule-1", 6, null, 540, 570, START, END,
                AppointmentStatus.CONFIRMED, CREATED, UPDATED));
        assertThrows(NullPointerException.class, () -> new Appointment("appointment-1",
                "patient-1", "Patient Fixture", "+8801712345678", "doctor-1", "Doctor Fixture",
                "Cardiology", 50025L, "schedule-1", 6, DATE, 540, 570, START, END,
                null, CREATED, UPDATED));
    }
}
