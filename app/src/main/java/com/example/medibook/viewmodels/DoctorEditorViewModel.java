package com.example.medibook.viewmodels;

import androidx.lifecycle.ViewModel;

import com.example.medibook.models.Doctor;
import com.example.medibook.repositories.DoctorMapper;
import com.example.medibook.repositories.DoctorRepository;
import com.google.android.gms.tasks.Task;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** A single server-confirmed Save, retained through rotation; never resubmitted by restoration. */
public final class DoctorEditorViewModel extends ViewModel {
    public static final class State {
        public final String doctorId;
        public final boolean editing;
        public final Doctor original;
        public final boolean loading;
        public final boolean saving;
        public final boolean completed;
        public final int revision;
        public final DoctorRepository.Error error;

        private State(DoctorEditorViewModel model) {
            doctorId = model.doctorId;
            editing = model.editing;
            original = model.original;
            loading = model.loading;
            saving = model.saving;
            completed = model.completed;
            revision = model.revision;
            error = model.error;
        }

        public boolean canSave() {
            return !loading && !saving && !completed && (!editing || original != null)
                    && (error == DoctorRepository.Error.NONE || error == DoctorRepository.Error.PERMISSION);
        }
    }

    public interface Listener { void onDoctorChanged(State state); }

    private final DoctorRepository repository = new DoctorRepository();
    private final Set<Listener> listeners = new HashSet<>();
    private String doctorId;
    private String owner;
    private Doctor original;
    private boolean initialized;
    private boolean editing;
    private boolean loading;
    private boolean saving;
    private boolean completed;
    private boolean needsReconciliation;
    private int generation;
    private int revision;
    private DoctorRepository.Error error = DoctorRepository.Error.NONE;

    public void initialize(String id, boolean editing, boolean restored) {
        if (initialized) return; // A retained ViewModel already owns the pending Task.
        initialized = true;
        doctorId = id == null ? repository.newDoctorId() : id;
        this.editing = editing;
        needsReconciliation = restored;
        if (!DoctorMapper.isValidId(doctorId)) error = DoctorRepository.Error.INVALID_DATA;
        else if (restored) error = DoctorRepository.Error.OUTCOME_UNKNOWN;
    }

    public State getState() { return new State(this); }
    public void addListener(Listener listener) { listeners.add(listener); listener.onDoctorChanged(getState()); }
    public void removeListener(Listener listener) { listeners.remove(listener); }

    public boolean startSession(String uid) {
        if (uid == null || (owner != null && !owner.equals(uid))) return false;
        owner = uid;
        if (editing && original == null && !loading && !saving && !needsReconciliation
                && error == DoctorRepository.Error.NONE) reload();
        else publish();
        return true;
    }

    public void endSession() {
        generation++;
        owner = null;
        original = null;
        saving = false;
        loading = false;
        completed = false;
        error = DoctorRepository.Error.SESSION_CHANGED;
    }

    /** Manual reload also reconciles a process interruption or an ambiguous create outcome. */
    public void reload() {
        if (!DoctorRepository.sameUser(owner) || loading || saving || !DoctorMapper.isValidId(doctorId)) return;
        int operation = ++generation;
        String uid = owner;
        loading = true;
        error = DoctorRepository.Error.NONE;
        publish();
        repository.loadDoctor(uid, doctorId).addOnCompleteListener(task -> {
            if (operation != generation || !DoctorRepository.sameUser(uid)) return;
            loading = false;
            needsReconciliation = false;
            if (task.isSuccessful()) {
                original = task.getResult();
                editing = true;
                revision++;
            } else {
                error = DoctorRepository.errorFor(task.getException());
                // A not-yet-created record may safely retry the SAME ID, never allocate another ID.
                if (!editing && error == DoctorRepository.Error.NOT_FOUND) error = DoctorRepository.Error.NONE;
            }
            publish();
        });
    }

    public void save(Doctor draft) {
        if (!DoctorRepository.sameUser(owner) || !getState().canSave() || !doctorId.equals(draft.getId())) return;
        int operation = ++generation;
        String uid = owner;
        saving = true;
        error = DoctorRepository.Error.NONE;
        publish();
        Task<Void> task = editing ? repository.editDoctor(uid, original, draft) : repository.addDoctor(uid, draft);
        task.addOnCompleteListener(result -> {
            if (operation != generation || !DoctorRepository.sameUser(uid)) return;
            saving = false;
            if (result.isSuccessful()) completed = true;
            else error = DoctorRepository.errorFor(result.getException());
            publish();
        });
    }

    private void publish() {
        State state = getState();
        for (Listener listener : new ArrayList<>(listeners)) listener.onDoctorChanged(state);
    }

    @Override
    protected void onCleared() {
        generation++;
        listeners.clear();
    }
}
