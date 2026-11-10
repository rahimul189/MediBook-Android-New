package com.example.medibook.viewmodels;

import androidx.lifecycle.ViewModel;

import com.example.medibook.models.Doctor;
import com.example.medibook.utils.PatientDoctorDirectory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Presentation state for server-confirmed Patient records; listener ownership stays with the screen. */
public final class PatientDoctorDirectoryViewModel extends ViewModel {
    public enum Status { NOT_CONNECTED, LOADING, READY, ERROR, PERMISSION }

    public static final class State {
        public final Status status;
        public final String query;
        public final String specialization;
        public final List<String> specializations;
        public final List<Doctor> doctors;
        public final int total;

        private State(PatientDoctorDirectoryViewModel model) {
            status = model.status;
            query = model.query;
            specialization = model.specialization;
            specializations = PatientDoctorDirectory.specializations(model.source);
            doctors = PatientDoctorDirectory.filter(model.source, query, specialization);
            total = model.source.size();
        }

        public boolean filtered() { return !query.trim().isEmpty() || !specialization.isEmpty(); }
    }

    public interface Listener { void onDirectoryChanged(State state); }

    private final Set<Listener> listeners = new HashSet<>();
    private String owner;
    private String query = "";
    private String specialization = ""; // Empty is All; localized labels never become filter identities.
    private List<Doctor> source = Collections.emptyList();
    private Status status = Status.NOT_CONNECTED;
    private State snapshot;

    public State getState() { if (snapshot == null) snapshot = new State(this); return snapshot; }
    public String getOwner() { return owner; }
    public void addListener(Listener listener) { listeners.add(listener); listener.onDirectoryChanged(getState()); }
    public void removeListener(Listener listener) { listeners.remove(listener); }

    public void bindSession(String uid) {
        if (uid == null || uid.isEmpty()) { endSession(); return; }
        if (uid.equals(owner)) return;
        if (!uid.equals(owner)) {
            clear();
            owner = uid;
        }
        publish();
    }

    public void endSession() { clear(); owner = null; publish(); }

    private void clear() {
        snapshot = null;
        source = Collections.emptyList();
        query = "";
        specialization = "";
        status = Status.NOT_CONNECTED;
    }

    public void setQuery(String value) {
        if (owner == null) return;
        String bounded = PatientDoctorDirectory.boundedQuery(value);
        if (query.equals(bounded)) return;
        query = bounded;
        publish();
    }

    /** Restore bounded UI criteria before the first server result provides available specializations. */
    public void restoreFilters(String query, String specialization) {
        if (owner == null) return;
        this.query = PatientDoctorDirectory.boundedQuery(query);
        this.specialization = specialization == null ? "" : PatientDoctorDirectory.boundedQuery(specialization).trim();
        publish();
    }

    public void setSpecialization(String value) {
        if (owner == null) return;
        String selected = "";
        for (String option : PatientDoctorDirectory.specializations(source)) {
            if (value != null && option.equalsIgnoreCase(value.trim())) { selected = option; break; }
        }
        if (selected.equals(specialization)) return;
        specialization = selected;
        publish();
    }

    public void clearFilters() {
        if (owner == null) return;
        query = "";
        specialization = "";
        publish();
    }

    // Inputs are accepted only for the verified screen owner.
    public void showDirectory(String forOwner, List<Doctor> supplied) {
        if (!owns(forOwner)) return;
        try {
            source = PatientDoctorDirectory.activeRecords(supplied);
            status = Status.READY;
            if (!PatientDoctorDirectory.specializations(source).contains(specialization)) specialization = "";
        } catch (IllegalArgumentException invalid) {
            source = Collections.emptyList();
            specialization = "";
            status = Status.ERROR;
        }
        publish();
    }

    public void showLoading(String forOwner) { showState(forOwner, Status.LOADING); }
    public void showError(String forOwner) { showState(forOwner, Status.ERROR); }
    /** A background refresh failure keeps already-verified rows visible instead of blanking them. */
    public void showRefreshFailure(String forOwner) {
        if (!owns(forOwner)) return;
        status = source.isEmpty() ? Status.ERROR : Status.READY;
        publish();
    }
    public void showPermission(String forOwner) { showState(forOwner, Status.PERMISSION); }
    public void showNotConnected(String forOwner) { showState(forOwner, Status.NOT_CONNECTED); }

    private void showState(String forOwner, Status next) {
        if (!owns(forOwner)) return;
        source = Collections.emptyList();
        status = next;
        publish();
    }

    private boolean owns(String forOwner) { return owner != null && owner.equals(forOwner); }

    private void publish() {
        State state = snapshot = new State(this);
        for (Listener listener : new ArrayList<>(listeners)) listener.onDirectoryChanged(state);
    }

    @Override protected void onCleared() { listeners.clear(); clear(); owner = null; }
}
