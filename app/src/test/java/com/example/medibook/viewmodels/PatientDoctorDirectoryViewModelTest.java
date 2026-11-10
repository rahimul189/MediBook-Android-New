package com.example.medibook.viewmodels;

import com.example.medibook.models.Doctor;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.example.medibook.viewmodels.PatientDoctorDirectoryViewModel.Status.*;
import static org.junit.Assert.*;

/** Synthetic records stay in JVM tests; the Activities never call showDirectory in this UI-only step. */
public class PatientDoctorDirectoryViewModelTest {
    @Test public void unchangedSessionAndStateReadsDoNotRefilterOrRepublishDirectory() {
        PatientDoctorDirectoryViewModel model = model();
        model.showDirectory(OWNER, Collections.singletonList(doctor("a", "Alpha", "Cardiology")));
        PatientDoctorDirectoryViewModel.State before = model.getState();
        int[] updates = {0}; model.addListener(state -> updates[0]++);
        model.bindSession(OWNER);
        assertSame(before, model.getState()); assertSame(before, model.getState()); assertEquals(1, updates[0]);
        model.setQuery("missing"); assertNotSame(before, model.getState()); assertEquals(2, updates[0]);
        assertEquals(1, before.doctors.size()); assertTrue(model.getState().doctors.isEmpty());
    }
    @Test public void restoredFiltersSurviveLoadingAndApplyToTheFirstServerResult() {
        PatientDoctorDirectoryViewModel model = model();
        model.restoreFilters("Alpha", "Cardiology");
        model.showLoading(OWNER);
        model.showError(OWNER);
        model.showDirectory(OWNER, Arrays.asList(doctor("a", "Alpha", "Cardiology"), doctor("b", "Beta", "Neurology")));
        assertEquals(1, model.getState().doctors.size());
        assertEquals("a", model.getState().doctors.get(0).getId());
        assertEquals("Cardiology", model.getState().specialization);
    }
    @Test public void liveUpdatesReapplySearchAndRemoveObsoleteSpecializations() {
        PatientDoctorDirectoryViewModel model = model();
        model.showDirectory(OWNER, Collections.singletonList(doctor("a", "Alpha", "Cardiology")));
        model.setQuery("Alpha");
        model.setSpecialization("Cardiology");
        model.showDirectory(OWNER, Collections.singletonList(doctor("b", "Alpha New", "Neurology")));
        assertEquals("", model.getState().specialization);
        assertEquals("b", model.getState().doctors.get(0).getId());
        model.showPermission(OWNER);
        assertEquals(PERMISSION, model.getState().status);
        assertTrue(model.getState().doctors.isEmpty());
    }
    private static final String OWNER = "patient-a";

    private Doctor doctor(String id, String name, String specialty) {
        return new Doctor(id, name, specialty, "Fixture qualification", new BigDecimal("500.00"), "", true);
    }

    private PatientDoctorDirectoryViewModel model() {
        PatientDoctorDirectoryViewModel model = new PatientDoctorDirectoryViewModel();
        model.bindSession(OWNER);
        return model;
    }

    @Test public void initialAndVerifiedStatesAreNotConnectedRatherThanAFabricatedLoadedEmptyDirectory() {
        PatientDoctorDirectoryViewModel model = new PatientDoctorDirectoryViewModel();
        assertEquals(NOT_CONNECTED, model.getState().status);
        assertNull(model.getOwner());
        model.bindSession(OWNER);
        assertEquals(NOT_CONNECTED, model.getState().status);
        assertEquals(0, model.getState().total);
        assertTrue(model.getState().doctors.isEmpty());
        assertTrue(model.getState().specializations.isEmpty());
    }

    @Test public void userInputDoesNotPretendToLoadDoctorData() {
        PatientDoctorDirectoryViewModel model = model();
        model.setQuery("Fixture");
        assertEquals(NOT_CONNECTED, model.getState().status);
        assertTrue(model.getState().doctors.isEmpty());
        assertTrue(model.getState().filtered());
    }

    @Test public void onlyAnExplicitSuppliedEmptyResultHasAReadyEmptyState() {
        PatientDoctorDirectoryViewModel model = model();
        model.showDirectory(OWNER, Collections.emptyList());
        assertEquals(READY, model.getState().status);
        assertTrue(model.getState().doctors.isEmpty());
        assertFalse(model.getState().filtered());
    }

    @Test public void queriesAndSpecializationsNarrowTheSameImmutableSource() {
        PatientDoctorDirectoryViewModel model = model();
        Doctor alpha = doctor("a", "Fixture Alpha", "Fixture Cardiology");
        model.showDirectory(OWNER, Arrays.asList(alpha, doctor("b", "Fixture Beta", "Fixture Neurology")));
        model.setSpecialization("fixture cardiology");
        model.setQuery("ALPHA");
        assertEquals(Collections.singletonList(alpha), model.getState().doctors);
        assertEquals("Fixture Cardiology", model.getState().specialization);
        assertEquals(2, model.getState().total);
        assertThrows(UnsupportedOperationException.class, () -> model.getState().doctors.clear());
        model.setQuery("missing");
        assertTrue(model.getState().doctors.isEmpty());
        assertEquals(READY, model.getState().status);
        assertTrue(model.getState().filtered());
    }

    @Test public void clearingFiltersRestoresAllActiveRowsWithoutChangingSourceData() {
        PatientDoctorDirectoryViewModel model = model();
        model.showDirectory(OWNER, Arrays.asList(doctor("a", "Fixture Alpha", "Fixture Cardiology"),
                doctor("b", "Fixture Beta", "Fixture Neurology"), doctor("hidden", "Fixture Hidden", "Fixture Other").withActive(false)));
        model.setQuery("missing");
        model.setSpecialization("Fixture Cardiology");
        model.clearFilters();
        assertEquals(2, model.getState().doctors.size());
        assertEquals(2, model.getState().specializations.size());
        assertFalse(model.getState().filtered());
    }

    @Test public void unknownOrRemovedSpecializationCannotHideAllRowsIndefinitely() {
        PatientDoctorDirectoryViewModel model = model();
        model.showDirectory(OWNER, Collections.singletonList(doctor("a", "Fixture Alpha", "Fixture Cardiology")));
        model.setSpecialization("not-an-option");
        assertEquals("", model.getState().specialization);
        model.setSpecialization("Fixture Cardiology");
        model.showDirectory(OWNER, Collections.singletonList(doctor("b", "Fixture Beta", "Fixture Neurology")));
        assertEquals("", model.getState().specialization);
        assertEquals(1, model.getState().doctors.size());
    }

    @Test public void dataSnapshotsDoNotShareMutableInputOrInventInactiveProfiles() {
        PatientDoctorDirectoryViewModel model = model();
        List<Doctor> input = new ArrayList<>(Collections.singletonList(doctor("a", "Fixture Alpha", "Fixture specialty")));
        model.showDirectory(OWNER, input);
        PatientDoctorDirectoryViewModel.State old = model.getState();
        input.clear();
        model.setQuery("missing");
        assertEquals(1, old.doctors.size());
        assertTrue(model.getState().doctors.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> old.specializations.clear());
    }

    @Test public void malformedInputAndDuplicateIdsShowErrorInsteadOfEmptySuccess() {
        PatientDoctorDirectoryViewModel model = model();
        model.showDirectory(OWNER, null);
        assertEquals(ERROR, model.getState().status);
        Doctor doctor = doctor("a", "Fixture Alpha", "Fixture specialty");
        model.showDirectory(OWNER, Arrays.asList(doctor, doctor));
        assertEquals(ERROR, model.getState().status);
        assertTrue(model.getState().doctors.isEmpty());
    }

    @Test public void loadingErrorAndRetryPresentationNeverKeepStaleDoctorRows() {
        PatientDoctorDirectoryViewModel model = model();
        model.showDirectory(OWNER, Collections.singletonList(doctor("a", "Fixture Alpha", "Fixture specialty")));
        model.showLoading(OWNER);
        assertEquals(LOADING, model.getState().status);
        assertTrue(model.getState().doctors.isEmpty());
        model.showError(OWNER);
        assertEquals(ERROR, model.getState().status);
        model.showNotConnected(OWNER);
        assertEquals(NOT_CONNECTED, model.getState().status);
        assertTrue(model.getState().doctors.isEmpty());
    }

    @Test public void recheckingTheSamePatientRetainsOnlyThatPatientsUiCriteria() {
        PatientDoctorDirectoryViewModel model = model();
        model.setQuery("Fixture Alpha");
        model.bindSession(OWNER);
        assertEquals("Fixture Alpha", model.getState().query);
        assertEquals(NOT_CONNECTED, model.getState().status);
    }

    @Test public void accountChangeClearsRowsAndCriteriaAndRejectsThePreviousOwnersInputs() {
        PatientDoctorDirectoryViewModel model = model();
        model.showDirectory(OWNER, Collections.singletonList(doctor("a", "Fixture Alpha", "Fixture specialty")));
        model.setQuery("Fixture Alpha");
        model.bindSession("patient-b");
        model.showDirectory(OWNER, Collections.singletonList(doctor("old", "Fixture Old", "Fixture specialty")));
        model.showLoading(OWNER);
        model.showError(OWNER);
        assertEquals(NOT_CONNECTED, model.getState().status);
        assertEquals("", model.getState().query);
        assertTrue(model.getState().doctors.isEmpty());
    }

    @Test public void sessionEndClearsDataAndIgnoresLaterInputs() {
        PatientDoctorDirectoryViewModel model = model();
        model.setQuery("Fixture");
        model.endSession();
        model.showDirectory(OWNER, Collections.singletonList(doctor("a", "Fixture Alpha", "Fixture specialty")));
        model.setQuery("cannot apply");
        model.setSpecialization("cannot apply");
        assertNull(model.getOwner());
        assertEquals("", model.getState().query);
        assertEquals(NOT_CONNECTED, model.getState().status);
        assertTrue(model.getState().doctors.isEmpty());
    }

    @Test public void detachedListenersDoNotRetainAStoppedScreenAndNewListenersSeeCurrentState() {
        PatientDoctorDirectoryViewModel model = model();
        int[] calls = {0};
        PatientDoctorDirectoryViewModel.Listener listener = state -> calls[0]++;
        model.addListener(listener);
        model.removeListener(listener);
        model.setQuery("Fixture");
        assertEquals(1, calls[0]);
        String[] query = {null};
        model.addListener(state -> query[0] = state.query);
        assertEquals("Fixture", query[0]);
        model.onCleared();
        model.setQuery("ignored");
        assertEquals("Fixture", query[0]);
    }

    @Test public void queryRestorationIsBoundedAndAnInvalidSessionDoesNotRetainIt() {
        PatientDoctorDirectoryViewModel model = model();
        model.setQuery("x".repeat(1000));
        assertEquals(100, model.getState().query.length());
        model.bindSession("");
        assertEquals("", model.getState().query);
        assertNull(model.getOwner());
    }
}
