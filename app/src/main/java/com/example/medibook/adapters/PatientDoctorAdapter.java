package com.example.medibook.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.medibook.R;
import com.example.medibook.databinding.ItemPatientDirectoryStateBinding;
import com.example.medibook.databinding.ItemPatientDoctorBinding;
import com.example.medibook.models.Doctor;
import com.example.medibook.utils.PatientDoctorDirectory;
import com.example.medibook.viewmodels.PatientDoctorDirectoryViewModel;

/** Read-only patient cards, never the Admin adapter or any CRUD actions. No sample data is supplied. */
public final class PatientDoctorAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface Listener {
        void onViewDoctor(Doctor doctor);
        void onClearFilters();
        void onRetryDirectory();
    }

    private static final int STATE = 0;
    private static final int DOCTOR = 1;
    private final Listener listener;
    private PatientDoctorDirectoryViewModel.State state;

    public PatientDoctorAdapter(Listener listener, PatientDoctorDirectoryViewModel.State initial) {
        this.listener = listener;
        state = initial;
    }

    /**
     * Dispatches only per-row changes. A full notifyDataSetChanged() inside a ConcatAdapter
     * becomes a host-wide invalidation, which detaches the focused header search field and closes
     * the keyboard after every typed character.
     */
    public void submit(PatientDoctorDirectoryViewModel.State next) {
        PatientDoctorDirectoryViewModel.State previous = state;
        state = next;
        PatientDoctorRowDiff.calculate(previous, next).dispatchUpdatesTo(this);
    }

    @Override public int getItemCount() { return PatientDoctorRowDiff.rowCount(state); }
    @Override public int getItemViewType(int position) { return PatientDoctorRowDiff.hasRecords(state) ? DOCTOR : STATE; }

    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        return type == DOCTOR ? new DoctorHolder(ItemPatientDoctorBinding.inflate(inflater, parent, false))
                : new StateHolder(ItemPatientDirectoryStateBinding.inflate(inflater, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof DoctorHolder) {
            Doctor doctor = state.doctors.get(position);
            ItemPatientDoctorBinding binding = ((DoctorHolder) holder).binding;
            Context context = holder.itemView.getContext();
            binding.doctorName.setText(doctor.getName());
            com.example.medibook.utils.DoctorImages.load(binding.doctorImage, doctor.getImageUrl());
            binding.specialization.setText(doctor.getSpecialization());
            binding.qualifications.setText(context.getString(R.string.patient_doctor_qualifications, doctor.getQualifications()));
            binding.consultationFee.setText(context.getString(R.string.doctor_fee_amount, PatientDoctorDirectory.feeText(doctor)));
            binding.viewProfileButton.setContentDescription(context.getString(R.string.patient_doctor_profile_accessibility, doctor.getName()));
            binding.viewProfileButton.setOnClickListener(view -> listener.onViewDoctor(doctor));
        } else bindState(((StateHolder) holder).binding);
    }

    private void bindState(ItemPatientDirectoryStateBinding binding) {
        boolean loading = state.status == PatientDoctorDirectoryViewModel.Status.LOADING;
        binding.loadingIndicator.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.stateIcon.setVisibility(loading ? View.GONE : View.VISIBLE);
        binding.stateAction.setVisibility(View.GONE);
        binding.stateAction.setOnClickListener(null);
        switch (state.status) {
            case NOT_CONNECTED:
            case LOADING:
                binding.stateHeading.setText(R.string.patient_directory_loading_title);
                binding.stateDescription.setText(R.string.patient_directory_loading_message);
                break;
            case PERMISSION:
            case ERROR:
                binding.stateHeading.setText(R.string.patient_directory_error_title);
                binding.stateDescription.setText(state.status == PatientDoctorDirectoryViewModel.Status.PERMISSION
                        ? R.string.patient_live_permission : R.string.patient_live_error);
                binding.stateAction.setVisibility(View.VISIBLE);
                binding.stateAction.setText(R.string.patient_directory_retry);
                binding.stateAction.setOnClickListener(view -> listener.onRetryDirectory());
                break;
            case READY:
                boolean noMatches = state.total > 0 && state.filtered();
                binding.stateHeading.setText(noMatches ? R.string.patient_directory_no_matches_title : R.string.patient_directory_empty_title);
                binding.stateDescription.setText(noMatches ? R.string.patient_directory_no_matches_message : R.string.patient_directory_empty_message);
                if (state.filtered()) {
                    binding.stateAction.setVisibility(View.VISIBLE);
                    binding.stateAction.setText(R.string.patient_directory_clear_filters);
                    binding.stateAction.setOnClickListener(view -> listener.onClearFilters());
                }
                break;
        }
    }

    @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof DoctorHolder) com.example.medibook.utils.DoctorImages.clear(((DoctorHolder) holder).binding.doctorImage);
        super.onViewRecycled(holder);
    }

    private static final class DoctorHolder extends RecyclerView.ViewHolder {
        final ItemPatientDoctorBinding binding;
        DoctorHolder(ItemPatientDoctorBinding binding) {
            super(binding.getRoot()); this.binding = binding;
            ViewCompat.setAccessibilityHeading(binding.doctorName, true);
        }
    }

    private static final class StateHolder extends RecyclerView.ViewHolder {
        final ItemPatientDirectoryStateBinding binding;
        StateHolder(ItemPatientDirectoryStateBinding binding) {
            super(binding.getRoot()); this.binding = binding;
            ViewCompat.setAccessibilityHeading(binding.stateHeading, true);
        }
    }
}
