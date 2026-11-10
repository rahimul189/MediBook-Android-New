package com.example.medibook.viewmodels;

import androidx.lifecycle.ViewModel;

import com.example.medibook.models.Doctor;
import com.example.medibook.repositories.DoctorRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Retains pending operations across rotation without retaining an Activity or its views. */
public final class DoctorListViewModel extends ViewModel {
    public static final class State {
        public final List<Doctor> doctors;
        public final boolean loading;
        public final boolean writing;
        public final DoctorRepository.Error error;

        private State(List<Doctor> doctors, boolean loading, boolean writing, DoctorRepository.Error error) {
            this.doctors = Collections.unmodifiableList(new ArrayList<>(doctors));
            this.loading = loading;
            this.writing = writing;
            this.error = error;
        }
    }

    public interface Listener { void onDoctorsChanged(State state); }

    private final DoctorRepository repository = new DoctorRepository();
    private final Set<Listener> listeners = new HashSet<>();
    private List<Doctor> doctors = Collections.emptyList();
    private String owner;
    private int generation;
    private boolean loading;
    private boolean writing;
    private boolean statusChanged;
    private long loadedAt;
    private DoctorRepository.Error error = DoctorRepository.Error.NONE;

    public State getState() { return new State(doctors, loading, writing, error); }
    public void addListener(Listener listener) { listeners.add(listener); listener.onDoctorsChanged(getState()); }
    public void removeListener(Listener listener) { listeners.remove(listener); }

    public void startSession(String uid) {
        if (uid == null) { endSession(); return; }
        if (!uid.equals(owner)) {
            // Do not publish an unconfirmed empty directory between authorization and the server read.
            clearState();
            owner = uid;
        }
        if (loadedAt == 0 || System.currentTimeMillis() - loadedAt > 30_000L) refresh();
        else publish();
    }

    public void endSession() {
        clearState();
        publish();
    }

    private void clearState() {
        generation++;
        owner = null;
        doctors = Collections.emptyList();
        loading = false;
        writing = false;
        statusChanged = false;
        loadedAt = 0;
        error = DoctorRepository.Error.NONE;
    }

    public void refresh() {
        if (!DoctorRepository.sameUser(owner) || loading || writing) return;
        int operation = ++generation;
        String uid = owner;
        loading = true;
        error = DoctorRepository.Error.NONE;
        publish();
        repository.loadDoctors(uid).addOnCompleteListener(task -> {
            if (operation != generation || !DoctorRepository.sameUser(uid)) return;
            loading = false;
            if (task.isSuccessful()) {
                doctors = task.getResult();
                loadedAt = System.currentTimeMillis();
            } else {
                doctors = Collections.emptyList();
                error = DoctorRepository.errorFor(task.getException());
            }
            publish();
        });
    }

    public void setActive(Doctor doctor, boolean active) {
        if (!DoctorRepository.sameUser(owner) || loading || writing || error != DoctorRepository.Error.NONE) return;
        int operation = ++generation;
        String uid = owner;
        writing = true;
        publish();
        repository.setActive(uid, doctor, active).addOnCompleteListener(task -> {
            if (operation != generation || !DoctorRepository.sameUser(uid)) return;
            writing = false;
            if (task.isSuccessful()) {
                // The transaction has been acknowledged by Firestore. Reload, never toggle optimistically.
                statusChanged = true;
                refresh();
            } else {
                error = DoctorRepository.errorFor(task.getException());
                publish();
            }
        });
    }

    public boolean takeStatusChanged() {
        boolean changed = statusChanged;
        statusChanged = false;
        return changed;
    }

    private void publish() {
        State state = getState();
        for (Listener listener : new ArrayList<>(listeners)) listener.onDoctorsChanged(state);
    }

    @Override
    protected void onCleared() {
        generation++;
        listeners.clear();
    }
}
