package com.example.medibook.adapters;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.medibook.R;
import com.example.medibook.databinding.ItemDoctorBinding;
import com.example.medibook.databinding.ItemDoctorListHeaderBinding;
import com.example.medibook.databinding.ItemDoctorListStateBinding;
import com.example.medibook.models.Doctor;
import com.example.medibook.repositories.DoctorRepository;
import com.example.medibook.utils.DoctorErrorMessages;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

/** One scrolling list with server-backed rows and explicit pending/error states. */
public final class DoctorAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface Listener {
        void onAddDoctor();
        void onEditDoctor(Doctor doctor);
        void onManageSchedule(Doctor doctor);
        void onToggleActive(Doctor doctor);
        void onRetry();
    }

    private static final int HEADER = 0;
    private static final int STATE = 1;
    private static final int DOCTOR = 2;
    private final Listener listener;
    private final List<Doctor> doctors = new ArrayList<>();
    private boolean loading = true;
    private boolean writing;
    private DoctorRepository.Error error = DoctorRepository.Error.NONE;

    public DoctorAdapter(Listener listener) {
        this.listener = listener;
    }

    // The directory has structural header/state rows. Refresh them together with this small dataset.
    @SuppressLint("NotifyDataSetChanged")
    public void submitState(List<Doctor> records, boolean loading, boolean writing, DoctorRepository.Error error) {
        doctors.clear();
        doctors.addAll(records);
        this.loading = loading;
        this.writing = writing;
        this.error = error;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return 1 + (hasPlaceholder() ? 1 : doctors.size());
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0) return HEADER;
        return hasPlaceholder() ? STATE : DOCTOR;
    }

    private boolean hasPlaceholder() { return loading || error != DoctorRepository.Error.NONE || doctors.isEmpty(); }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == HEADER) {
            return new HeaderHolder(ItemDoctorListHeaderBinding.inflate(inflater, parent, false));
        }
        if (viewType == STATE) {
            return new StateHolder(ItemDoctorListStateBinding.inflate(inflater, parent, false));
        }
        return new DoctorHolder(ItemDoctorBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeaderHolder) {
            ItemDoctorListHeaderBinding binding = ((HeaderHolder) holder).binding;
            binding.addDoctorButton.setEnabled(!loading && !writing && error == DoctorRepository.Error.NONE);
            binding.addDoctorButton.setOnClickListener(view -> listener.onAddDoctor());
            binding.refreshButton.setEnabled(!loading && !writing);
            binding.refreshButton.setOnClickListener(view -> listener.onRetry());
            binding.operationProgress.setVisibility(writing ? View.VISIBLE : View.GONE);
            binding.operationStatus.setVisibility(writing ? View.VISIBLE : View.GONE);
        } else if (holder instanceof StateHolder) {
            bindState(((StateHolder) holder).binding);
        } else {
            bindDoctor(((DoctorHolder) holder).binding, doctors.get(position - 1));
        }
    }

    private void bindState(ItemDoctorListStateBinding binding) {
        boolean failed = error != DoctorRepository.Error.NONE;
        binding.loadingIndicator.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.stateIcon.setVisibility(loading ? View.GONE : View.VISIBLE);
        binding.stateHeading.setText(loading ? R.string.doctor_loading_heading
                : failed ? R.string.doctor_error_heading : R.string.doctor_empty_heading);
        binding.stateDescription.setText(loading ? R.string.doctor_loading_description
                : failed ? DoctorErrorMessages.forError(error) : R.string.doctor_empty_description);
        binding.retryButton.setVisibility(failed ? View.VISIBLE : View.GONE);
        binding.retryButton.setOnClickListener(view -> listener.onRetry());
    }

    private void bindDoctor(ItemDoctorBinding binding, Doctor doctor) {
        Context context = binding.getRoot().getContext();
        binding.doctorName.setText(doctor.getName());
        binding.doctorSpecialization.setText(doctor.getSpecialization());
        NumberFormat feeFormat = NumberFormat.getNumberInstance();
        feeFormat.setMinimumFractionDigits(2);
        feeFormat.setMaximumFractionDigits(2);
        binding.consultationFee.setText(context.getString(R.string.doctor_fee_amount,
                feeFormat.format(doctor.getConsultationFee())));
        binding.activeStatus.setText(doctor.isActive()
                ? R.string.doctor_active_status : R.string.doctor_inactive_status);
        binding.activeStatus.setChipBackgroundColor(ColorStateList.valueOf(ContextCompat.getColor(
                context, doctor.isActive() ? R.color.medibook_teal_container : R.color.medibook_background)));
        binding.activeStatus.setTextColor(ContextCompat.getColor(context,
                doctor.isActive() ? R.color.medibook_teal_dark : R.color.medibook_text_secondary));
        binding.editButton.setContentDescription(context.getString(
                R.string.doctor_edit_accessibility, doctor.getName()));
        binding.statusButton.setText(doctor.isActive()
                ? R.string.deactivate_doctor_button : R.string.activate_doctor_button);
        binding.statusButton.setContentDescription(context.getString(doctor.isActive()
                ? R.string.doctor_deactivate_accessibility : R.string.doctor_activate_accessibility,
                doctor.getName()));
        binding.editButton.setOnClickListener(view -> listener.onEditDoctor(doctor));
        binding.statusButton.setOnClickListener(view -> listener.onToggleActive(doctor));
        binding.scheduleButton.setContentDescription(context.getString(
                R.string.schedule_manage_accessibility, doctor.getName()));
        binding.scheduleButton.setOnClickListener(view -> listener.onManageSchedule(doctor));
        binding.editButton.setEnabled(!writing && !loading);
        binding.statusButton.setEnabled(!writing && !loading);
        binding.scheduleButton.setEnabled(!writing && !loading);
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final ItemDoctorListHeaderBinding binding;

        HeaderHolder(ItemDoctorListHeaderBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            ViewCompat.setAccessibilityHeading(binding.directoryHeading, true);
        }
    }

    private static final class StateHolder extends RecyclerView.ViewHolder {
        final ItemDoctorListStateBinding binding;

        StateHolder(ItemDoctorListStateBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            ViewCompat.setAccessibilityHeading(binding.stateHeading, true);
        }
    }

    private static final class DoctorHolder extends RecyclerView.ViewHolder {
        final ItemDoctorBinding binding;

        DoctorHolder(ItemDoctorBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            ViewCompat.setAccessibilityHeading(binding.doctorName, true);
        }
    }
}
