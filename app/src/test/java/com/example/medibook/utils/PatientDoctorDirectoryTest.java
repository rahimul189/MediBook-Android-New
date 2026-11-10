package com.example.medibook.utils;

import com.example.medibook.models.Doctor;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.*;

/** Synthetic JVM fixtures only; no sample doctor is included in the application or sent to Firebase. */
public class PatientDoctorDirectoryTest {
    private Doctor doctor(String id, String name, String specialization, boolean active) {
        return new Doctor(id, name, specialization, "Fixture qualification", new BigDecimal("500.25"), "Fixture biography", active);
    }

    @Test public void emptyInputNeverCreatesSampleDoctors() {
        assertTrue(PatientDoctorDirectory.activeRecords(Collections.emptyList()).isEmpty());
        assertTrue(PatientDoctorDirectory.specializations(Collections.emptyList()).isEmpty());
        assertTrue(PatientDoctorDirectory.filter(Collections.emptyList(), "", "").isEmpty());
    }

    @Test public void onlyActiveValidRecordsAreShownInStableNameOrder() {
        List<Doctor> active = PatientDoctorDirectory.activeRecords(Arrays.asList(
                doctor("z", "Fixture Beta", "Fixture specialty", true),
                doctor("b", "Fixture Alpha", "Fixture specialty", true),
                doctor("a", "fixture alpha", "Fixture specialty", true),
                doctor("hidden", "Fixture Hidden", "Fixture other", false)));
        assertEquals(3, active.size());
        assertEquals("a", active.get(0).getId());
        assertEquals("b", active.get(1).getId());
        assertEquals("z", active.get(2).getId());
    }

    @Test public void activeRecordsAreAnImmutableCopyRatherThanSharedMutableInput() {
        List<Doctor> input = new ArrayList<>(Collections.singletonList(doctor("a", "Fixture Alpha", "Fixture specialty", true)));
        List<Doctor> snapshot = PatientDoctorDirectory.activeRecords(input);
        input.clear();
        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }

    @Test public void invalidOrDuplicateRecordsProduceAnErrorInsteadOfMisleadingEmptyResults() {
        assertThrows(IllegalArgumentException.class, () -> PatientDoctorDirectory.activeRecords(null));
        assertThrows(IllegalArgumentException.class, () -> PatientDoctorDirectory.activeRecords(Collections.singletonList(null)));
        assertThrows(IllegalArgumentException.class, () -> PatientDoctorDirectory.activeRecords(Collections.singletonList(
                doctor("bad/id", "Fixture Alpha", "Fixture specialty", true))));
        assertThrows(IllegalArgumentException.class, () -> PatientDoctorDirectory.activeRecords(Arrays.asList(
                doctor("a", "Fixture Alpha", "Fixture specialty", true), doctor("a", "Fixture Beta", "Fixture specialty", true))));
    }

    @Test public void profileValidationRejectsUnsupportedDetailsAndFeePrecision() {
        assertFalse(PatientDoctorDirectory.validDoctor(null));
        assertFalse(PatientDoctorDirectory.validDoctor(doctor("a", " ", "Fixture specialty", true)));
        assertFalse(PatientDoctorDirectory.validDoctor(doctor("a", "Fixture Alpha", "S".repeat(101), true)));
        assertFalse(PatientDoctorDirectory.validDoctor(new Doctor("a", "Fixture Alpha", "Fixture specialty", "Fixture qualification",
                new BigDecimal("1.234"), "", true)));
        assertTrue(PatientDoctorDirectory.validDoctor(doctor("a", "Fixture Alpha", "Fixture specialty", true)));
    }

    @Test public void nameSearchIgnoresCaseAndEdgeWhitespace() {
        Doctor alpha = doctor("a", "Fixture Alpha", "Fixture specialty", true);
        List<Doctor> input = PatientDoctorDirectory.activeRecords(Arrays.asList(alpha, doctor("b", "Fixture Beta", "Fixture other", true)));
        assertEquals(Collections.singletonList(alpha), PatientDoctorDirectory.filter(input, "  ALPHA  ", ""));
        assertEquals(2, PatientDoctorDirectory.filter(input, null, null).size());
    }

    @Test public void nameSearchDoesNotSearchUnrelatedFieldsOrInterpretRegularExpressions() {
        List<Doctor> input = Collections.singletonList(doctor("a", "Fixture Alpha", "Fixture specialty", true));
        assertTrue(PatientDoctorDirectory.filter(input, "specialty", "").isEmpty());
        assertTrue(PatientDoctorDirectory.filter(input, ".*", "").isEmpty());
    }

    @Test public void nameAndSpecializationFiltersAreCombinedWithAnd() {
        Doctor alpha = doctor("a", "Fixture Alpha", "Fixture Cardiology", true);
        List<Doctor> input = Arrays.asList(alpha, doctor("b", "Fixture Alpha Two", "Fixture Neurology", true));
        assertEquals(Collections.singletonList(alpha), PatientDoctorDirectory.filter(input, "Alpha", " fixture cardiology "));
        assertTrue(PatientDoctorDirectory.filter(input, "missing", "Fixture Cardiology").isEmpty());
    }

    @Test public void specializationChoicesAreSortedDeduplicatedAndDerivedOnlyFromActiveRecords() {
        List<Doctor> input = Arrays.asList(doctor("a", "Fixture Alpha", "Fixture Neurology", true),
                doctor("b", "Fixture Beta", "Fixture Cardiology", true),
                doctor("c", "Fixture Charlie", " fixture cardiology ", true),
                doctor("d", "Fixture Hidden", "Fixture Hidden Specialty", false));
        assertEquals(Arrays.asList("Fixture Cardiology", "Fixture Neurology"), PatientDoctorDirectory.specializations(input));
        assertThrows(UnsupportedOperationException.class, () -> PatientDoctorDirectory.specializations(input).clear());
    }

    @Test public void inactiveRecordsCannotLeakThroughFilteringEvenWithAllSelected() {
        assertTrue(PatientDoctorDirectory.filter(Collections.singletonList(
                doctor("hidden", "Fixture Hidden", "Fixture specialty", false)), "", "").isEmpty());
    }

    @Test public void specializationDeduplicationAndMatchingUseTheSameUnicodeCasePolicy() {
        List<Doctor> input = Arrays.asList(doctor("a", "Fixture Alpha", "Internal Medicine", true),
                doctor("b", "Fixture Beta", "ınternal medicine", true));
        List<String> options = PatientDoctorDirectory.specializations(input);
        assertEquals(1, options.size());
        assertEquals(2, PatientDoctorDirectory.filter(input, "", options.get(0)).size());
    }

    @Test public void internationalNamesAndTurkishDeviceLocaleDoNotBreakCaseMatching() {
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            Doctor doctor = doctor("a", "FIXTURE ISLAM বাংলা", "Fixture specialty", true);
            assertEquals(1, PatientDoctorDirectory.filter(Collections.singletonList(doctor), "islam বাংলা", "").size());
        } finally { Locale.setDefault(old); }
    }

    @Test public void queryStateHasABoundedLengthAndNullIsEmpty() {
        assertEquals("", PatientDoctorDirectory.boundedQuery(null));
        assertEquals(100, PatientDoctorDirectory.boundedQuery("x".repeat(1000)).length());
        assertEquals(" a ", PatientDoctorDirectory.boundedQuery(" a "));
    }

    @Test public void feeDisplayUsesTwoDecimalsWithoutInventingACurrencyOrChangingTheAmount() {
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            Doctor doctor = doctor("a", "Fixture Alpha", "Fixture specialty", true);
            assertEquals("500.25", PatientDoctorDirectory.feeText(doctor));
            assertEquals(new BigDecimal("500.25"), doctor.getConsultationFee());
            Doctor zero = new Doctor("zero", "Fixture Zero", "Fixture specialty", "Fixture qualification", BigDecimal.ZERO, "", true);
            assertEquals("0.00", PatientDoctorDirectory.feeText(zero));
        } finally { Locale.setDefault(old); }
    }
}
