package com.example.medibook.viewmodels;

import androidx.lifecycle.ViewModel;

import com.example.medibook.models.DoctorSchedule;
import com.example.medibook.repositories.DoctorMapper;
import com.example.medibook.repositories.DoctorScheduleRepository;
import com.example.medibook.repositories.DoctorScheduleStore;
import com.google.android.gms.tasks.TaskExecutors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/** Retains online reads/writes across rotation without keeping Activity references. */
public final class DoctorScheduleListViewModel extends ViewModel {
    public com.google.android.gms.tasks.Task<Integer> publication;
    public long publicationDate = -1;
    public boolean publicationDateChosen;
    public String publicationFeedback;
    public static final class State {
        public final String doctorName;
        public final List<DoctorSchedule> schedules;
        public final boolean loaded;
        public final boolean loading;
        public final boolean writing;
        public final String busyId;
        public final DoctorScheduleRepository.Error error;

        private State(DoctorScheduleListViewModel model) {
            doctorName = model.directory == null ? null : model.directory.doctor.getName();
            schedules = model.directory == null ? Collections.emptyList() : model.directory.schedules;
            loaded = model.directory != null;
            loading = model.loading;
            writing = model.writing;
            busyId = model.busyId;
            error = model.error;
        }

        public boolean canAct() { return loaded && !loading && !writing && error == DoctorScheduleRepository.Error.NONE; }
    }

    public interface Listener { void onSchedulesChanged(State state); }

    private final DoctorScheduleStore repository;
    private final Executor callbacks;
    private final Set<Listener> listeners = new HashSet<>();
    private String doctorId;
    private String owner;
    private DoctorScheduleRepository.Directory directory;
    private boolean loading;
    private boolean writing;
    private String busyId;
    private boolean statusSaved;
    private int generation;
    private boolean ended;
    private long loadedAt;
    private DoctorScheduleRepository.Error error = DoctorScheduleRepository.Error.NONE;

    public DoctorScheduleListViewModel() { this(new DoctorScheduleRepository(), TaskExecutors.MAIN_THREAD); }

    DoctorScheduleListViewModel(DoctorScheduleStore repository, Executor callbacks) {
        this.repository = repository;
        this.callbacks = callbacks;
    }

    public void initialize(String doctorId) {
        if (this.doctorId != null) return;
        this.doctorId = doctorId;
        if (!DoctorMapper.isValidId(doctorId)) error = DoctorScheduleRepository.Error.INVALID_DATA;
    }

    public State getState() { return new State(this); }
    public void addListener(Listener listener) { listeners.add(listener); listener.onSchedulesChanged(getState()); }
    public void removeListener(Listener listener) { listeners.remove(listener); }

    public boolean startSession(String uid) {
        if (ended || uid == null || !repository.isCurrentUser(uid) || (owner != null && !owner.equals(uid))) return false;
        owner = uid;
        if (directory == null || System.currentTimeMillis() - loadedAt > 30_000L) refresh();
        else publish();
        return true;
    }

    public void endSession() {
        generation++;
        ended = true;
        owner = null;
        directory = null;
        loading = false;
        writing = false;
        busyId = null;
        statusSaved = false;
        publicationFeedback = null;
        publicationDate = -1;
        publicationDateChosen = false;
        error = DoctorScheduleRepository.Error.SESSION_CHANGED;
    }

    public void refresh() {
        if (ended || !repository.isCurrentUser(owner) || !DoctorMapper.isValidId(doctorId) || loading || writing) return;
        String uid = owner;
        int operation = ++generation;
        loading = true;
        error = DoctorScheduleRepository.Error.NONE;
        publish();
        repository.load(uid, doctorId).addOnCompleteListener(callbacks, task -> {
            if (operation != generation || !repository.isCurrentUser(uid)) return;
            loading = false;
            if (task.isSuccessful()) { directory = task.getResult(); loadedAt = System.currentTimeMillis(); }
            else {
                directory = null;
                error = DoctorScheduleRepository.errorFor(task.getException());
            }
            publish();
        });
    }

    public void setActive(DoctorSchedule original, boolean active) {
        if (ended || !repository.isCurrentUser(owner) || !getState().canAct() || original == null
                || original.isActive() == active) return;
        String uid = owner;
        int operation = ++generation;
        writing = true;
        busyId = original.getId();
        publish();
        repository.setActive(uid, doctorId, original, active).addOnCompleteListener(callbacks, task -> {
            if (operation != generation || !repository.isCurrentUser(uid)) return;
            writing = false;
            busyId = null;
            if (task.isSuccessful()) {
                statusSaved = true;
                refresh(); // Never change a row optimistically or claim success from a cached snapshot.
            } else {
                error = DoctorScheduleRepository.errorFor(task.getException());
                publish();
            }
        });
    }

    public boolean takeStatusSaved() {
        boolean result = statusSaved;
        statusSaved = false;
        return result;
    }

    private void publish() {
        State state = getState();
        for (Listener listener : new ArrayList<>(listeners)) listener.onSchedulesChanged(state);
    }

    @Override protected void onCleared() { ended = true; generation++; listeners.clear(); }
}
