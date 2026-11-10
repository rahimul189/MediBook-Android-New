package com.example.medibook.adapters;

import androidx.recyclerview.widget.DiffUtil;

import com.example.medibook.models.Doctor;
import com.example.medibook.viewmodels.PatientDoctorDirectoryViewModel;
import com.example.medibook.viewmodels.PatientDoctorDirectoryViewModel.State;

/**
 * Granular row updates for the Patient directory results.
 *
 * <p>The search header is a separate item of a {@link androidx.recyclerview.widget.ConcatAdapter}.
 * ConcatAdapter promotes a nested {@code notifyDataSetChanged()} to a host-wide
 * {@code notifyDataSetChanged()}, which invalidates every attached holder, including the header's
 * focused search field. Per-row changes keep the header attached and its IME connection alive.
 */
final class PatientDoctorRowDiff extends DiffUtil.Callback {
    private final State before;
    private final State after;

    private PatientDoctorRowDiff(State before, State after) {
        this.before = before;
        this.after = after;
    }

    static DiffUtil.DiffResult calculate(State before, State after) {
        return DiffUtil.calculateDiff(new PatientDoctorRowDiff(before, after));
    }

    /** The results adapter shows one state row whenever there are no filtered records. */
    static boolean hasRecords(State state) {
        return state.status == PatientDoctorDirectoryViewModel.Status.READY
                && !state.doctors.isEmpty();
    }

    static int rowCount(State state) {
        return hasRecords(state) ? state.doctors.size() : 1;
    }

    @Override public int getOldListSize() { return rowCount(before); }
    @Override public int getNewListSize() { return rowCount(after); }

    @Override public boolean areItemsTheSame(int oldPosition, int newPosition) {
        boolean beforeRecords = hasRecords(before);
        boolean afterRecords = hasRecords(after);
        if (beforeRecords != afterRecords) return false;
        if (!beforeRecords) return true;
        return before.doctors.get(oldPosition).getId().equals(after.doctors.get(newPosition).getId());
    }

    @Override public boolean areContentsTheSame(int oldPosition, int newPosition) {
        boolean beforeRecords = hasRecords(before);
        boolean afterRecords = hasRecords(after);
        if (beforeRecords != afterRecords) return false;
        if (!beforeRecords) {
            // The single state row renders loading, empty, no-match or error presentation.
            return before.status == after.status
                    && before.total == after.total
                    && before.filtered() == after.filtered();
        }
        return sameDirectoryContent(before.doctors.get(oldPosition), after.doctors.get(newPosition));
    }

    /** Only the fields displayed on a directory card affect the result row. */
    private static boolean sameDirectoryContent(Doctor oldDoctor, Doctor newDoctor) {
        return oldDoctor.getName().equals(newDoctor.getName())
                && oldDoctor.getSpecialization().equals(newDoctor.getSpecialization())
                && oldDoctor.getQualifications().equals(newDoctor.getQualifications())
                && oldDoctor.getConsultationFee().equals(newDoctor.getConsultationFee())
                && oldDoctor.getImageUrl().equals(newDoctor.getImageUrl());
    }
}
