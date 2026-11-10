package com.example.medibook.viewmodels;

import com.example.medibook.models.Doctor;
import com.example.medibook.models.DoctorSchedule;
import com.example.medibook.repositories.DoctorScheduleRepository;
import com.example.medibook.repositories.DoctorScheduleStore;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.firebase.Timestamp;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.example.medibook.repositories.DoctorScheduleRepository.Error.*;
import static org.junit.Assert.*;

/** Deterministic Task completion, no Firebase app/Android main loop or remote access. */
public class DoctorScheduleViewModelTest {
    private static final String OWNER = "admin-a";
    private static final String DOCTOR = "doctor-a";

    private static final class FakeStore implements DoctorScheduleStore {
        String currentUser = OWNER;
        int ids;
        int adds;
        int edits;
        int statusWrites;
        String lastDoctor;
        String lastOwner;
        DoctorSchedule lastDraft;
        DoctorSchedule lastOriginal;
        final List<TaskCompletionSource<DoctorScheduleRepository.Directory>> loads = new ArrayList<>();
        final List<TaskCompletionSource<Void>> writes = new ArrayList<>();

        @Override public boolean isCurrentUser(String uid) { return uid != null && uid.equals(currentUser); }
        @Override public String newScheduleId(String doctorId) { return "allocated-" + (++ids); }
        @Override public Task<DoctorScheduleRepository.Directory> load(String owner, String doctorId) {
            lastOwner = owner;
            lastDoctor = doctorId;
            TaskCompletionSource<DoctorScheduleRepository.Directory> source = new TaskCompletionSource<>();
            loads.add(source);
            return source.getTask();
        }
        private Task<Void> write(String owner, String doctorId, DoctorSchedule original, DoctorSchedule draft) {
            lastOwner = owner;
            lastDoctor = doctorId;
            lastOriginal = original;
            lastDraft = draft;
            TaskCompletionSource<Void> source = new TaskCompletionSource<>();
            writes.add(source);
            return source.getTask();
        }
        @Override public Task<Void> add(String owner, String doctorId, DoctorSchedule draft) {
            adds++;
            return write(owner, doctorId, null, draft);
        }
        @Override public Task<Void> edit(String owner, String doctorId, DoctorSchedule original, DoctorSchedule draft) {
            edits++;
            return write(owner, doctorId, original, draft);
        }
        @Override public Task<Void> setActive(String owner, String doctorId, DoctorSchedule original, boolean active) {
            statusWrites++;
            return write(owner, doctorId, original, original.withActive(active));
        }
    }

    private DoctorSchedule saved(String id) {
        return new DoctorSchedule(id, 1, 540, 600, 15, true, new Timestamp(100, 123));
    }

    private DoctorSchedule draft(String id) { return new DoctorSchedule(id, 1, 540, 600, 15, true); }

    private DoctorScheduleRepository.Directory directory(DoctorSchedule... entries) {
        Doctor doctor = new Doctor(DOCTOR, "Fixture doctor", "Fixture specialty", "Fixture qualification",
                new BigDecimal("500.00"), "", true);
        return new DoctorScheduleRepository.Directory(doctor, Arrays.asList(entries));
    }

    private DoctorScheduleRepository.Failure unavailable() {
        return new DoctorScheduleRepository.Failure(NETWORK);
    }

    private DoctorScheduleEditorViewModel newEditor(FakeStore store) {
        DoctorScheduleEditorViewModel model = new DoctorScheduleEditorViewModel(store, Runnable::run);
        model.initialize(DOCTOR, null, false, false);
        assertTrue(model.startSession(OWNER));
        store.loads.get(0).setResult(directory());
        return model;
    }

    private DoctorScheduleListViewModel newList(FakeStore store, DoctorSchedule... entries) {
        DoctorScheduleListViewModel model = new DoctorScheduleListViewModel(store, Runnable::run);
        model.initialize(DOCTOR);
        assertTrue(model.startSession(OWNER));
        store.loads.get(0).setResult(directory(entries));
        return model;
    }

    @Test public void editorLoadsBeforeSavingAndAllocatesOnlyOneStableId() {
        FakeStore store = new FakeStore();
        DoctorScheduleEditorViewModel model = new DoctorScheduleEditorViewModel(store, Runnable::run);
        model.initialize(DOCTOR, null, false, false);
        assertFalse(model.getState().canSave());
        assertTrue(store.loads.isEmpty());
        assertTrue(model.startSession(OWNER));
        model.save(draft(model.getState().scheduleId));
        assertTrue(model.getState().loading);
        assertTrue(store.writes.isEmpty());
        store.loads.get(0).setResult(directory());
        assertTrue(model.getState().canSave());
        assertEquals("allocated-1", model.getState().scheduleId);
        assertEquals(1, store.ids);
        assertEquals("Fixture doctor", model.getState().doctorName);
    }

    @Test public void editorBlocksDuplicateSaveAndDoesNotCompleteBeforeAcknowledgement() {
        FakeStore store = new FakeStore();
        DoctorScheduleEditorViewModel model = newEditor(store);
        DoctorSchedule draft = draft(model.getState().scheduleId);
        model.save(draft);
        model.save(draft);
        model.reload();
        assertTrue(model.getState().saving);
        assertFalse(model.getState().completed);
        assertFalse(model.getState().canSave());
        assertEquals(1, store.writes.size());
        assertEquals(1, store.loads.size());
        store.writes.get(0).setResult(null);
        assertTrue(model.getState().completed);
        assertFalse(model.getState().saving);
        model.save(draft);
        assertEquals(1, store.adds);
        assertEquals(DOCTOR, store.lastDoctor);
        assertEquals(OWNER, store.lastOwner);
    }

    @Test public void retainedEditorIgnoresReinitializationAndDoesNotRepeatTheTask() {
        FakeStore store = new FakeStore();
        DoctorScheduleEditorViewModel model = newEditor(store);
        model.save(draft(model.getState().scheduleId));
        model.initialize("another-doctor", "other-id", true, true);
        assertTrue(model.startSession(OWNER));
        assertTrue(model.getState().saving);
        assertEquals("allocated-1", model.getState().scheduleId);
        assertEquals(1, store.ids);
        assertEquals(1, store.adds);
        assertEquals(1, store.loads.size());
    }

    @Test public void detachedObserverReceivesNoCallbacksAndReattachedObserverSeesCompletedState() {
        FakeStore store = new FakeStore();
        DoctorScheduleEditorViewModel model = newEditor(store);
        int[] notifications = {0};
        DoctorScheduleEditorViewModel.Listener oldScreen = state -> notifications[0]++;
        model.addListener(oldScreen);
        model.removeListener(oldScreen);
        model.save(draft(model.getState().scheduleId));
        store.writes.get(0).setResult(null);
        assertEquals(1, notifications[0]);
        boolean[] completed = {false};
        model.addListener(state -> completed[0] = state.completed);
        assertTrue(completed[0]);
    }

    @Test public void interruptedNewEditorRequiresExplicitReadAndNeverResubmitsAutomatically() {
        FakeStore store = new FakeStore();
        DoctorScheduleEditorViewModel model = new DoctorScheduleEditorViewModel(store, Runnable::run);
        model.initialize(DOCTOR, "stable-before-interruption", false, true);
        assertTrue(model.startSession(OWNER));
        assertEquals(OUTCOME_UNKNOWN, model.getState().error);
        assertTrue(store.loads.isEmpty());
        assertTrue(store.writes.isEmpty());
        model.save(draft("stable-before-interruption"));
        assertTrue(store.writes.isEmpty());
        model.reload();
        store.loads.get(0).setResult(directory());
        assertTrue(model.getState().canSave());
        model.save(draft("stable-before-interruption"));
        assertEquals("stable-before-interruption", store.lastDraft.getId());
        assertEquals(0, store.ids);
        assertEquals(1, store.adds);
    }

    @Test public void interruptedNewSaveAlreadyOnServerBecomesAnEditWithoutClaimingSuccess() {
        FakeStore store = new FakeStore();
        DoctorScheduleEditorViewModel model = new DoctorScheduleEditorViewModel(store, Runnable::run);
        model.initialize(DOCTOR, "stable-id", false, true);
        model.startSession(OWNER);
        model.reload();
        DoctorSchedule original = saved("stable-id");
        store.loads.get(0).setResult(directory(original));
        assertTrue(model.getState().editing);
        assertFalse(model.getState().completed);
        assertSame(original, model.getState().original);
        assertTrue(store.writes.isEmpty());
        model.save(draft("stable-id"));
        assertEquals(1, store.edits);
        assertEquals(0, store.adds);
        assertSame(original, store.lastOriginal);
    }

    @Test public void restorationWithMissingIdentityCannotAllocateADuplicatePath() {
        FakeStore store = new FakeStore();
        DoctorScheduleEditorViewModel model = new DoctorScheduleEditorViewModel(store, Runnable::run);
        model.initialize(DOCTOR, null, false, true);
        model.startSession(OWNER);
        model.reload();
        assertEquals(INVALID_DATA, model.getState().error);
        assertEquals(0, store.ids);
        assertTrue(store.loads.isEmpty());
    }

    @Test public void editUsesServerVersionAndPreservesBothPathIdentities() {
        FakeStore store = new FakeStore();
        DoctorScheduleEditorViewModel model = new DoctorScheduleEditorViewModel(store, Runnable::run);
        model.initialize(DOCTOR, "existing", true, false);
        model.startSession(OWNER);
        DoctorSchedule original = saved("existing");
        store.loads.get(0).setResult(directory(original));
        model.save(draft("existing"));
        assertEquals(1, store.edits);
        assertEquals(0, store.adds);
        assertSame(original, store.lastOriginal);
        assertEquals("existing", store.lastDraft.getId());
        assertEquals(DOCTOR, store.lastDoctor);
    }

    @Test public void missingEditTargetCannotBecomeAnUpsert() {
        FakeStore store = new FakeStore();
        DoctorScheduleEditorViewModel model = new DoctorScheduleEditorViewModel(store, Runnable::run);
        model.initialize(DOCTOR, "missing", true, false);
        model.startSession(OWNER);
        store.loads.get(0).setResult(directory());
        model.save(draft("missing"));
        assertEquals(NOT_FOUND, model.getState().error);
        assertFalse(model.getState().loaded);
        assertFalse(model.getState().canSave());
        assertTrue(store.writes.isEmpty());
    }

    @Test public void invalidOrWrongIdentityDraftCannotBeSubmitted() {
        FakeStore store = new FakeStore();
        DoctorScheduleEditorViewModel model = newEditor(store);
        model.save(null);
        model.save(draft("other-id"));
        model.save(new DoctorSchedule(model.getState().scheduleId, 1, 600, 540, 15, true));
        assertTrue(store.writes.isEmpty());
    }

    @Test public void unconfirmedWriteBlocksSaveUntilReloadAndKeepsItsId() {
        FakeStore store = new FakeStore();
        DoctorScheduleEditorViewModel model = newEditor(store);
        String id = model.getState().scheduleId;
        model.save(draft(id));
        store.writes.get(0).setException(unavailable());
        assertEquals(NETWORK, model.getState().error);
        assertFalse(model.getState().completed);
        model.save(draft(id));
        assertEquals(1, store.writes.size());
        model.reload();
        store.loads.get(1).setResult(directory());
        assertEquals(id, model.getState().scheduleId);
        assertTrue(model.getState().canSave());
        assertEquals(1, store.ids);
    }

    @Test public void failedReloadDoesNotLeaveTheOldVersionSavable() {
        FakeStore store = new FakeStore();
        DoctorScheduleEditorViewModel model = newEditor(store);
        model.reload();
        store.loads.get(1).setException(unavailable());
        assertFalse(model.getState().loaded);
        assertFalse(model.getState().canSave());
        assertEquals(NETWORK, model.getState().error);
        model.reload();
        store.loads.get(2).setResult(directory());
        assertTrue(model.getState().canSave());
    }

    @Test public void overlapFailureAllowsCorrectingTheFormWithoutPretendingItWasSaved() {
        FakeStore store = new FakeStore();
        DoctorScheduleEditorViewModel model = newEditor(store);
        DoctorSchedule candidate = draft(model.getState().scheduleId);
        model.save(candidate);
        store.writes.get(0).setException(new DoctorScheduleRepository.Failure(OVERLAP));
        assertEquals(OVERLAP, model.getState().error);
        assertFalse(model.getState().completed);
        assertTrue(model.getState().canSave());
        model.save(candidate.withActive(false));
        assertEquals(2, store.adds);
        assertFalse(store.lastDraft.isActive());
    }

    @Test public void conflictingEditRequiresExplicitReloadOfTheNewServerVersion() {
        FakeStore store = new FakeStore();
        DoctorScheduleEditorViewModel model = new DoctorScheduleEditorViewModel(store, Runnable::run);
        model.initialize(DOCTOR, "existing", true, false);
        model.startSession(OWNER);
        store.loads.get(0).setResult(directory(saved("existing")));
        int oldRevision = model.getState().revision;
        model.save(draft("existing"));
        store.writes.get(0).setException(new DoctorScheduleRepository.Failure(CONFLICT));
        assertFalse(model.getState().canSave());
        assertFalse(model.getState().completed);
        model.save(draft("existing"));
        assertEquals(1, store.edits);
        model.reload();
        DoctorSchedule latest = new DoctorSchedule("existing", 2, 660, 720, 20, false, new Timestamp(200, 456));
        store.loads.get(1).setResult(directory(latest));
        assertTrue(model.getState().canSave());
        assertSame(latest, model.getState().original);
        assertTrue(model.getState().revision > oldRevision);
        assertEquals("existing", model.getState().scheduleId);
    }

    @Test public void permissionFailureRequiresReloadRatherThanRepeatedSaves() {
        FakeStore store = new FakeStore();
        DoctorScheduleEditorViewModel model = newEditor(store);
        model.save(draft(model.getState().scheduleId));
        store.writes.get(0).setException(new DoctorScheduleRepository.Failure(PERMISSION));
        assertEquals(PERMISSION, model.getState().error);
        assertFalse(model.getState().canSave());
        assertFalse(model.getState().completed);
        assertEquals(1, store.writes.size());
    }

    @Test public void editorSessionLossClearsDataAndIgnoresLateSaveCompletion() {
        FakeStore store = new FakeStore();
        DoctorScheduleEditorViewModel model = newEditor(store);
        model.save(draft(model.getState().scheduleId));
        model.endSession();
        store.writes.get(0).setResult(null);
        assertFalse(model.getState().completed);
        assertFalse(model.getState().saving);
        assertNull(model.getState().doctorName);
        assertEquals(SESSION_CHANGED, model.getState().error);
        assertFalse(model.startSession(OWNER));
    }

    @Test public void changedAccountCannotStartOrCompleteEditorWork() {
        FakeStore store = new FakeStore();
        DoctorScheduleEditorViewModel model = newEditor(store);
        model.save(draft(model.getState().scheduleId));
        store.currentUser = "another-admin";
        assertFalse(model.startSession("another-admin"));
        store.writes.get(0).setResult(null);
        assertFalse(model.getState().completed);
        model.endSession();
        assertNull(model.getState().original);
    }

    @Test public void clearedEditorIgnoresLateReadCallbacks() {
        FakeStore store = new FakeStore();
        DoctorScheduleEditorViewModel model = new DoctorScheduleEditorViewModel(store, Runnable::run);
        model.initialize(DOCTOR, "existing", true, false);
        model.startSession(OWNER);
        model.onCleared();
        store.loads.get(0).setResult(directory(saved("existing")));
        assertNull(model.getState().original);
        assertFalse(model.startSession(OWNER));
    }

    @Test public void listStartsUnloadedAndEmptyWithoutInventingEntries() {
        FakeStore store = new FakeStore();
        DoctorScheduleListViewModel model = new DoctorScheduleListViewModel(store, Runnable::run);
        model.initialize(DOCTOR);
        assertFalse(model.getState().loaded);
        assertFalse(model.getState().canAct());
        assertTrue(model.getState().schedules.isEmpty());
        model.startSession(OWNER);
        assertTrue(model.getState().loading);
        store.loads.get(0).setResult(directory());
        assertTrue(model.getState().loaded);
        assertTrue(model.getState().canAct());
        assertTrue(model.getState().schedules.isEmpty());
    }

    @Test public void listBlocksDuplicateStatusWritesAndRefreshDuringTheWrite() {
        FakeStore store = new FakeStore();
        DoctorSchedule original = saved("existing");
        DoctorScheduleListViewModel model = newList(store, original);
        model.setActive(original, false);
        model.setActive(original, false);
        model.refresh();
        assertTrue(model.getState().writing);
        assertEquals("existing", model.getState().busyId);
        assertFalse(model.getState().canAct());
        assertEquals(1, store.statusWrites);
        assertEquals(1, store.loads.size());
        assertTrue(model.getState().schedules.get(0).isActive());
        assertFalse(model.takeStatusSaved());
    }

    @Test public void listStatusSuccessIsOneShotAndRowsWaitForServerReload() {
        FakeStore store = new FakeStore();
        DoctorSchedule original = saved("existing");
        DoctorScheduleListViewModel model = newList(store, original);
        model.setActive(original, false);
        store.writes.get(0).setResult(null);
        assertTrue(model.getState().loading);
        assertTrue(model.getState().schedules.get(0).isActive());
        assertTrue(model.takeStatusSaved());
        assertFalse(model.takeStatusSaved());
        store.loads.get(1).setResult(directory(original.withActive(false)));
        assertFalse(model.getState().schedules.get(0).isActive());
        assertEquals(1, model.getState().schedules.size());
    }

    @Test public void failedStatusWriteNeverChangesTheRowOrReportsSuccess() {
        FakeStore store = new FakeStore();
        DoctorSchedule original = saved("existing");
        DoctorScheduleListViewModel model = newList(store, original);
        model.setActive(original, false);
        store.writes.get(0).setException(unavailable());
        assertEquals(NETWORK, model.getState().error);
        assertTrue(model.getState().schedules.get(0).isActive());
        assertFalse(model.takeStatusSaved());
        model.setActive(original, false);
        assertEquals(1, store.statusWrites);
        model.refresh();
        store.loads.get(1).setResult(directory(original));
        assertTrue(model.getState().canAct());
    }

    @Test public void failedListReadClearsStaleRowsAndRetryFetchesAgain() {
        FakeStore store = new FakeStore();
        DoctorScheduleListViewModel model = newList(store, saved("existing"));
        model.refresh();
        store.loads.get(1).setException(unavailable());
        assertEquals(NETWORK, model.getState().error);
        assertFalse(model.getState().loaded);
        assertTrue(model.getState().schedules.isEmpty());
        model.refresh();
        store.loads.get(2).setResult(directory());
        assertTrue(model.getState().canAct());
    }

    @Test public void duplicateReadIsSuppressedAndListSessionLossIgnoresLateRead() {
        FakeStore store = new FakeStore();
        DoctorScheduleListViewModel model = new DoctorScheduleListViewModel(store, Runnable::run);
        model.initialize(DOCTOR);
        model.startSession(OWNER);
        model.refresh();
        assertEquals(1, store.loads.size());
        model.endSession();
        store.loads.get(0).setResult(directory(saved("existing")));
        assertTrue(model.getState().schedules.isEmpty());
        assertNull(model.getState().doctorName);
        assertEquals(SESSION_CHANGED, model.getState().error);
        assertFalse(model.startSession(OWNER));
    }

    @Test public void listSessionLossIgnoresLateStatusAcknowledgement() {
        FakeStore store = new FakeStore();
        DoctorSchedule original = saved("existing");
        DoctorScheduleListViewModel model = newList(store, original);
        model.setActive(original, false);
        model.endSession();
        store.writes.get(0).setResult(null);
        assertFalse(model.takeStatusSaved());
        assertTrue(model.getState().schedules.isEmpty());
        assertEquals(1, store.loads.size());
    }

    @Test public void invalidDoctorContextCannotLoadOrAllocateAnId() {
        FakeStore store = new FakeStore();
        DoctorScheduleListViewModel list = new DoctorScheduleListViewModel(store, Runnable::run);
        list.initialize("bad/id");
        list.startSession(OWNER);
        list.refresh();
        DoctorScheduleEditorViewModel editor = new DoctorScheduleEditorViewModel(store, Runnable::run);
        editor.initialize("bad/id", null, false, false);
        editor.startSession(OWNER);
        editor.reload();
        assertEquals(INVALID_DATA, list.getState().error);
        assertEquals(INVALID_DATA, editor.getState().error);
        assertTrue(store.loads.isEmpty());
        assertEquals(0, store.ids);
    }

    @Test public void directoriesAreSortedImmutableSnapshotsAndCannotBeSharedByMutation() {
        DoctorSchedule friday = new DoctorSchedule("friday", 5, 540, 600, 15, true);
        DoctorSchedule late = new DoctorSchedule("late", 1, 660, 720, 15, true);
        List<DoctorSchedule> input = new ArrayList<>(Arrays.asList(friday, late, saved("z"), saved("a")));
        DoctorScheduleRepository.Directory directory = new DoctorScheduleRepository.Directory(directory().doctor, input);
        input.clear();
        assertEquals(4, directory.schedules.size());
        assertEquals("a", directory.schedules.get(0).getId());
        assertEquals("z", directory.schedules.get(1).getId());
        assertEquals("late", directory.schedules.get(2).getId());
        assertEquals("friday", directory.schedules.get(3).getId());
        assertThrows(UnsupportedOperationException.class, directory.schedules::clear);
        assertEquals("a", directory.find("a").getId());
        assertNull(directory.find("missing"));
        assertTrue(directory().schedules.isEmpty());
    }

    @Test public void repositoryErrorsUnwrapNestedFailuresWithoutExposingMessages() {
        assertEquals(INVALID_DATA, DoctorScheduleRepository.errorFor(new IllegalArgumentException("fixture")));
        assertEquals(NETWORK, DoctorScheduleRepository.errorFor(new RuntimeException(unavailable())));
        assertEquals(PERMISSION, DoctorScheduleRepository.errorFor(new DoctorScheduleRepository.Failure(PERMISSION)));
        assertEquals(CONFLICT, DoctorScheduleRepository.errorFor(new DoctorScheduleRepository.Failure(CONFLICT)));
        assertEquals(UNKNOWN, DoctorScheduleRepository.errorFor(null));
    }
}
