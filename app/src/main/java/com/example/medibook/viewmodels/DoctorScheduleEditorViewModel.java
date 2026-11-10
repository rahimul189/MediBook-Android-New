package com.example.medibook.viewmodels;

import androidx.lifecycle.ViewModel;

import com.example.medibook.models.DoctorSchedule;
import com.example.medibook.repositories.DoctorMapper;
import com.example.medibook.repositories.DoctorScheduleRepository;
import com.example.medibook.repositories.DoctorScheduleStore;
import com.example.medibook.utils.DoctorScheduleValidation;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskExecutors;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

/** One retained Save Task and server version; no automatic resubmission after process restoration. */
public final class DoctorScheduleEditorViewModel extends ViewModel {
    public static final class State {
        public final String scheduleId;
        public final String doctorName;
        public final DoctorSchedule original;
        public final boolean editing;
        public final boolean loaded;
        public final boolean loading;
        public final boolean saving;
        public final boolean completed;
        public final int revision;
        public final DoctorScheduleRepository.Error error;

        private State(DoctorScheduleEditorViewModel model) {
            scheduleId = model.scheduleId;
            doctorName = model.doctorName;
            original = model.original;
            editing = model.editing;
            loaded = model.loaded;
            loading = model.loading;
            saving = model.saving;
            completed = model.completed;
            revision = model.revision;
            error = model.error;
        }

        public boolean canInteract() { return loaded && !loading && !saving && !completed; }
        public boolean canSave() {
            return canInteract() && (!editing || original != null) && (error == DoctorScheduleRepository.Error.NONE
                    || error == DoctorScheduleRepository.Error.OVERLAP);
        }
    }

    public interface Listener { void onScheduleChanged(State state); }

    private final DoctorScheduleStore repository;
    private final Executor callbacks;
    private final Set<Listener> listeners = new HashSet<>();
    private boolean initialized;
    private String doctorId;
    private String scheduleId;
    private String doctorName;
    private String owner;
    private DoctorSchedule original;
    private boolean editing;
    private boolean loaded;
    private boolean loading;
    private boolean saving;
    private boolean completed;
    private boolean needsReconciliation;
    private int revision;
    private int generation;
    private boolean ended;
    private DoctorScheduleRepository.Error error = DoctorScheduleRepository.Error.NONE;

    public DoctorScheduleEditorViewModel() { this(new DoctorScheduleRepository(), TaskExecutors.MAIN_THREAD); }

    DoctorScheduleEditorViewModel(DoctorScheduleStore repository, Executor callbacks) {
        this.repository = repository;
        this.callbacks = callbacks;
    }

    public void initialize(String doctorId, String scheduleId, boolean editing, boolean restored) {
        if (initialized) return; // A rotation reuses the ViewModel and its in-flight Task.
        initialized = true;
        this.doctorId = doctorId;
        this.editing = editing;
        if (!DoctorMapper.isValidId(doctorId)
                || ((editing || restored || scheduleId != null) && !DoctorScheduleValidation.validId(scheduleId))) {
            error = DoctorScheduleRepository.Error.INVALID_DATA;
            return;
        }
        this.scheduleId = scheduleId == null ? repository.newScheduleId(doctorId) : scheduleId;
        needsReconciliation = restored;
        if (restored) error = DoctorScheduleRepository.Error.OUTCOME_UNKNOWN;
    }

    public State getState() { return new State(this); }
    public void addListener(Listener listener) { listeners.add(listener); listener.onScheduleChanged(getState()); }
    public void removeListener(Listener listener) { listeners.remove(listener); }

    public boolean startSession(String uid) {
        if (ended || uid == null || !repository.isCurrentUser(uid) || (owner != null && !owner.equals(uid))) return false;
        owner = uid;
        if (!loaded && !loading && !saving && !needsReconciliation && error == DoctorScheduleRepository.Error.NONE) reload();
        else publish();
        return true;
    }

    public void endSession() {
        generation++;
        ended = true;
        owner = null;
        original = null;
        doctorName = null;
        loaded = false;
        loading = false;
        saving = false;
        completed = false;
        error = DoctorScheduleRepository.Error.SESSION_CHANGED;
    }

    public void reload() {
        if (ended || !repository.isCurrentUser(owner) || loading || saving || completed
                || !DoctorScheduleValidation.validId(scheduleId)) return;
        String uid = owner;
        int operation = ++generation;
        loading = true;
        loaded = false;
        error = DoctorScheduleRepository.Error.NONE;
        publish();
        repository.load(uid, doctorId).addOnCompleteListener(callbacks, task -> {
            if (operation != generation || !repository.isCurrentUser(uid)) return;
            loading = false;
            needsReconciliation = false;
            if (task.isSuccessful()) {
                doctorName = task.getResult().doctor.getName();
                DoctorSchedule saved = task.getResult().find(scheduleId);
                if (saved == null && editing) {
                    original = null;
                    error = DoctorScheduleRepository.Error.NOT_FOUND;
                }
                else {
                    loaded = true;
                    if (saved != null) {
                        original = saved;
                        editing = true;
                        revision++;
                    }
                }
            } else error = DoctorScheduleRepository.errorFor(task.getException());
            publish();
        });
    }

    public void save(DoctorSchedule draft) {
        if (ended || !repository.isCurrentUser(owner) || !getState().canSave()
                || !DoctorScheduleValidation.isValid(draft) || !scheduleId.equals(draft.getId())) return;
        String uid = owner;
        int operation = ++generation;
        saving = true;
        error = DoctorScheduleRepository.Error.NONE;
        publish();
        Task<Void> task = editing ? repository.edit(uid, doctorId, original, draft) : repository.add(uid, doctorId, draft);
        task.addOnCompleteListener(callbacks, result -> {
            if (operation != generation || !repository.isCurrentUser(uid)) return;
            saving = false;
            if (result.isSuccessful()) completed = true;
            else error = DoctorScheduleRepository.errorFor(result.getException());
            publish();
        });
    }

    private void publish() {
        State state = getState();
        for (Listener listener : new ArrayList<>(listeners)) listener.onScheduleChanged(state);
    }

    @Override protected void onCleared() { ended = true; generation++; listeners.clear(); }
}
