package com.example.medibook.repositories;

import com.example.medibook.models.Doctor;
import com.google.firebase.Timestamp;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.Assert.*;

/** Offline mapping only. Fixtures are never sent to Firebase or included as application data. */
public class DoctorMapperTest {
    @Test public void patientFeeExampleUsesIntegerHundredthsWithoutRounding() {
        Map<String, Object> values = data();
        values.put("consultationFee", 50000L);
        assertEquals("500.00", DoctorMapper.fromMap("doctor-1", values).getConsultationFee().toPlainString());
    }
    private Map<String, Object> data() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Fixture Name");
        data.put("specialization", "Fixture specialty");
        data.put("qualifications", "Fixture qualification");
        data.put("consultationFee", 50025L);
        data.put("biography", "Fixture biography");
        data.put("active", true);
        data.put("createdAt", new Timestamp(100, 0));
        data.put("updatedAt", new Timestamp(200, 123));
        return data;
    }

    private void assertInvalid(Map<String, Object> data) {
        assertThrows(IllegalArgumentException.class, () -> DoctorMapper.fromMap("doctor-1", data));
    }

    @Test
    public void mapsEveryStoredFieldAndDocumentIdentity() {
        Doctor doctor = DoctorMapper.fromMap("doctor-1", data());
        assertEquals("doctor-1", doctor.getId());
        assertEquals("Fixture Name", doctor.getName());
        assertEquals("Fixture specialty", doctor.getSpecialization());
        assertEquals("Fixture qualification", doctor.getQualifications());
        assertEquals("Fixture biography", doctor.getBiography());
        assertEquals(new BigDecimal("500.25"), doctor.getConsultationFee());
        assertTrue(doctor.isActive());
        assertEquals(new Timestamp(100, 0), doctor.getCreatedAt());
        assertEquals(new Timestamp(200, 123), doctor.getUpdatedAt());
    }

    @Test
    public void zeroAndMaximumFeesRemainExact() {
        Map<String, Object> values = data();
        values.put("consultationFee", 0L);
        assertEquals(new BigDecimal("0.00"), DoctorMapper.fromMap("doctor-1", values).getConsultationFee());
        values.put("consultationFee", 99999999L);
        assertEquals(new BigDecimal("999999.99"), DoctorMapper.fromMap("doctor-1", values).getConsultationFee());
    }

    @Test
    public void rejectsFloatingPointStringAndOutOfRangeFees() {
        for (Object value : new Object[]{50025.0, "50025", -1L, 100000000L, Double.NaN, Double.POSITIVE_INFINITY}) {
            Map<String, Object> values = data();
            values.put("consultationFee", value);
            assertInvalid(values);
        }
    }

    @Test
    public void rejectsEveryMissingField() {
        for (String field : data().keySet()) {
            Map<String, Object> values = data();
            values.remove(field);
            assertInvalid(values);
        }
    }

    @Test
    public void rejectsUnexpectedFieldsIncludingAnEditableId() {
        for (String field : new String[]{"id", "role", "ownerUid", "schedule"}) {
            Map<String, Object> values = data();
            values.put(field, "unexpected");
            assertInvalid(values);
        }
    }

    @Test
    public void rejectsNullAndWrongTypes() {
        for (String field : data().keySet()) {
            Map<String, Object> values = data();
            values.put(field, null);
            assertInvalid(values);
            values.put(field, new Object());
            assertInvalid(values);
        }
    }

    @Test
    public void rejectsStringAndNumericStatus() {
        Map<String, Object> values = data();
        values.put("active", "true");
        assertInvalid(values);
        values.put("active", 1L);
        assertInvalid(values);
    }

    @Test
    public void rejectsInvalidNamesAndOverlongBiography() {
        Map<String, Object> values = data();
        values.put("name", "\u00a0  ");
        assertInvalid(values);
        values = data();
        values.put("biography", "B".repeat(1001));
        assertInvalid(values);
    }

    @Test
    public void rejectsNonServerTimestampTypesAndReversedChronology() {
        Map<String, Object> values = data();
        values.put("createdAt", 123L);
        assertInvalid(values);
        values = data();
        values.put("updatedAt", new Timestamp(99, 0));
        assertInvalid(values);
    }

    @Test
    public void allowsEqualCreateTimestampsAndMultilineBiography() {
        Map<String, Object> values = data();
        values.put("updatedAt", values.get("createdAt"));
        values.put("biography", "First line.\nSecond line.");
        assertEquals("First line.\nSecond line.", DoctorMapper.fromMap("doctor-1", values).getBiography());
    }

    @Test
    public void idsCannotContainPathsOrUnboundedInput() {
        for (String id : new String[]{"", "../doctor", "doctors/id", "x".repeat(129)}) {
            assertFalse(DoctorMapper.isValidId(id));
            assertThrows(IllegalArgumentException.class, () -> DoctorMapper.fromMap(id, data()));
        }
        assertTrue(DoctorMapper.isValidId("doctor_1-AbC"));
    }

    @Test
    public void createUsesOnlyAllowedFieldsAndTheSameTimestampSentinel() {
        Doctor doctor = DoctorMapper.fromMap("doctor-1", data());
        Object serverTimestamp = new Object();
        Map<String, Object> created = DoctorMapper.createMap(doctor, serverTimestamp);
        java.util.Set<String> expected = new HashSet<>(data().keySet()); expected.add("imageUrl");
        assertEquals(expected, created.keySet());
        assertEquals("", created.get("imageUrl"));
        assertSame(serverTimestamp, created.get("createdAt"));
        assertSame(serverTimestamp, created.get("updatedAt"));
        assertEquals(50025L, created.get("consultationFee"));
        assertFalse(created.containsKey("id"));
    }

    @Test
    public void editNeverSendsIdOrCreatedAtFromTheClientModel() {
        Doctor doctor = DoctorMapper.fromMap("doctor-1", data());
        Object serverTimestamp = new Object();
        Map<String, Object> update = DoctorMapper.updateMap(doctor, serverTimestamp);
        assertEquals(8, update.size());
        assertFalse(update.containsKey("createdAt"));
        assertFalse(update.containsKey("id"));
        assertSame(serverTimestamp, update.get("updatedAt"));
    }

    @Test
    public void statusChangeOnlySendsActiveAndUpdatedAt() {
        Object serverTimestamp = new Object();
        Map<String, Object> update = DoctorMapper.statusMap(false, serverTimestamp);
        assertEquals(new HashSet<>(Arrays.asList("active", "updatedAt")), update.keySet());
        assertEquals(false, update.get("active"));
        assertSame(serverTimestamp, update.get("updatedAt"));
    }

    @Test
    public void invalidDraftCannotBeMappedForWriting() {
        Doctor draft = new Doctor("doctor-1", "Fixture Name", "Fixture specialty", "Fixture qualification",
                new BigDecimal("12.345"), "", true);
        assertThrows(IllegalArgumentException.class, () -> DoctorMapper.updateMap(draft, new Object()));
    }
}
