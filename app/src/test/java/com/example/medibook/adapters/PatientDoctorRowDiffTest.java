package com.example.medibook.adapters;

import androidx.recyclerview.widget.ListUpdateCallback;

import com.example.medibook.models.Doctor;
import com.example.medibook.viewmodels.PatientDoctorDirectoryViewModel;
import com.example.medibook.viewmodels.PatientDoctorDirectoryViewModel.State;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * Regression coverage for the Patient Doctor Name Search keyboard bug.
 *
 * <p>The search field lives in a ConcatAdapter header. A nested notifyDataSetChanged() becomes a
 * host-wide invalidation that detaches the focused header, so every search-driven result update
 * must dispatch granular row operations instead.
 */
public class PatientDoctorRowDiffTest {
    private static final String OWNER = "patient-a";

    @Test public void firstSearchCharacterNarrowsRowsWithGranularRemovals() {
        PatientDoctorDirectoryViewModel model = modelWith(
                doctor("a", "Fixture Alpha", "Fixture Cardiology"),
                doctor("b", "Fixture Beta", "Fixture Neurology"),
                doctor("c", "Fixture Gamma", "Fixture Cardiology"));
        State before = model.getState();

        model.setQuery("fixture a");
        Recorder recorder = dispatch(before, model.getState());

        assertEquals(2, recorder.removed);
        assertEquals(0, recorder.inserted);
        assertEquals(0, recorder.changed);
        assertEquals(0, recorder.moved);
    }

    @Test public void continuousTypingAndClearingStayGranularAndRestoreAllRows() {
        PatientDoctorDirectoryViewModel model = modelWith(
                doctor("a", "Fixture Alpha", "Fixture Cardiology"),
                doctor("b", "Fixture Beta", "Fixture Neurology"),
                doctor("c", "Fixture Gamma", "Fixture Cardiology"));
        State initial = model.getState();

        model.setQuery("fixture");
        State allMatching = model.getState();
        Recorder unchanged = dispatch(initial, allMatching);
        assertNoUpdates(unchanged);

        model.setQuery("fixture b");
        State oneMatch = model.getState();
        Recorder narrowed = dispatch(allMatching, oneMatch);
        assertEquals(2, narrowed.removed);
        assertEquals(0, narrowed.inserted);

        model.clearFilters();
        State restored = model.getState();
        Recorder widened = dispatch(oneMatch, restored);
        assertEquals(2, widened.inserted);
        assertEquals(0, widened.removed);
        assertEquals(3, PatientDoctorRowDiff.rowCount(restored));
    }

    @Test public void searchAndSpecializationCombineWithoutReplacingTheWholeList() {
        PatientDoctorDirectoryViewModel model = modelWith(
                doctor("a", "Fixture Alpha", "Fixture Cardiology"),
                doctor("b", "Fixture Beta", "Fixture Cardiology"),
                doctor("c", "Fixture Gamma", "Fixture Neurology"));
        model.setSpecialization("Fixture Cardiology");
        State specialtyOnly = model.getState();

        model.setQuery("fixture beta");
        State narrowed = model.getState();
        Recorder recorder = dispatch(specialtyOnly, narrowed);

        assertEquals(1, PatientDoctorRowDiff.rowCount(narrowed));
        assertEquals(1, recorder.removed);
        assertEquals(0, recorder.inserted);
    }

    @Test public void noMatchesReplaceDoctorRowsWithTheStateRowAndClearingRestoresThem() {
        PatientDoctorDirectoryViewModel model = modelWith(
                doctor("a", "Fixture Alpha", "Fixture Cardiology"),
                doctor("b", "Fixture Beta", "Fixture Neurology"));
        State records = model.getState();

        model.setQuery("missing");
        State noMatches = model.getState();
        Recorder hidden = dispatch(records, noMatches);
        assertEquals(2, hidden.removed);
        assertEquals(1, hidden.inserted);

        model.clearFilters();
        State restored = model.getState();
        Recorder restoredRecorder = dispatch(noMatches, restored);
        assertEquals(1, restoredRecorder.removed);
        assertEquals(2, restoredRecorder.inserted);
    }

    @Test public void stateRowPresentationChangesUseAChangedNotification() {
        PatientDoctorDirectoryViewModel model = modelWith(
                doctor("a", "Fixture Alpha", "Fixture Cardiology"));
        model.showLoading(OWNER);
        State loading = model.getState();
        model.showError(OWNER);
        State error = model.getState();

        Recorder recorder = dispatch(loading, error);

        assertEquals(1, recorder.changed);
        assertEquals(0, recorder.removed);
        assertEquals(0, recorder.inserted);
    }

    @Test public void liveDoctorContentChangesRebindOnlyTheChangedCard() {
        PatientDoctorDirectoryViewModel model = modelWith(
                doctor("a", "Fixture Alpha", "Fixture Cardiology"),
                doctor("b", "Fixture Beta", "Fixture Neurology"));
        State before = model.getState();
        model.showDirectory(OWNER, Arrays.asList(
                doctor("a", "Fixture Alpha Updated", "Fixture Cardiology"),
                doctor("b", "Fixture Beta", "Fixture Neurology")));
        State after = model.getState();

        Recorder recorder = dispatch(before, after);

        assertEquals(1, recorder.changed);
        assertEquals(0, recorder.removed);
        assertEquals(0, recorder.inserted);
    }

    @Test public void imageUrlChangesRefreshTheAvatarWithoutReplacingSearchHeader() {
        Doctor doctor = doctor("a", "Fixture Alpha", "Fixture Cardiology");
        PatientDoctorDirectoryViewModel model = modelWith(doctor);
        State before = model.getState();
        model.showDirectory(OWNER, java.util.Collections.singletonList(doctor.withImageUrl("https://example.org/doctor.jpg")));
        Recorder recorder = dispatch(before, model.getState());
        assertEquals(1, recorder.changed); assertEquals(0, recorder.inserted); assertEquals(0, recorder.removed);
    }

    private Doctor doctor(String id, String name, String specialization) {
        return new Doctor(id, name, specialization, "Fixture qualification",
                new BigDecimal("500.00"), "", true);
    }

    private PatientDoctorDirectoryViewModel modelWith(Doctor... doctors) {
        PatientDoctorDirectoryViewModel model = new PatientDoctorDirectoryViewModel();
        model.bindSession(OWNER);
        model.showDirectory(OWNER, Arrays.asList(doctors));
        return model;
    }

    private Recorder dispatch(State before, State after) {
        Recorder recorder = new Recorder();
        PatientDoctorRowDiff.calculate(before, after).dispatchUpdatesTo(recorder);
        return recorder;
    }

    private void assertNoUpdates(Recorder recorder) {
        assertEquals(0, recorder.inserted);
        assertEquals(0, recorder.removed);
        assertEquals(0, recorder.changed);
        assertEquals(0, recorder.moved);
    }

    private static final class Recorder implements ListUpdateCallback {
        int inserted;
        int removed;
        int changed;
        int moved;
        final List<String> events = new ArrayList<>();

        @Override public void onInserted(int position, int count) {
            inserted += count;
            events.add("insert " + position + " x" + count);
        }

        @Override public void onRemoved(int position, int count) {
            removed += count;
            events.add("remove " + position + " x" + count);
        }

        @Override public void onMoved(int fromPosition, int toPosition) {
            moved++;
            events.add("move " + fromPosition + " -> " + toPosition);
        }

        @Override public void onChanged(int position, int count, Object payload) {
            changed += count;
            events.add("change " + position + " x" + count);
        }
    }
}
