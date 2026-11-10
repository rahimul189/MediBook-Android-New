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
import com.example.medibook.databinding.ItemDoctorScheduleBinding;
import com.example.medibook.databinding.ItemDoctorScheduleHeaderBinding;
import com.example.medibook.databinding.ItemDoctorScheduleStateBinding;
import com.example.medibook.models.DoctorSchedule;
import com.example.medibook.repositories.DoctorScheduleRepository;
import com.example.medibook.utils.DoctorScheduleUi;
import com.example.medibook.utils.DoctorScheduleErrorMessages;

import java.util.ArrayList;
import java.util.List;

/** Server-backed schedule directory with loading, pending and retry states. */
public final class DoctorScheduleAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface Listener {
        void onAddSchedule();
        void onEditSchedule(DoctorSchedule entry);
        void onToggleSchedule(DoctorSchedule entry);
        void onRetrySchedules();
    }

    private static final int HEADER = 0;
    private static final int STATE = 1;
    private static final int ENTRY = 2;
    private final Listener listener;
    private final List<DoctorSchedule> entries = new ArrayList<>();
    private String doctorName;
    private boolean validContext;
    private boolean loading;
    private boolean writing;
    private String busyId;
    private DoctorScheduleRepository.Error error = DoctorScheduleRepository.Error.NONE;

    public DoctorScheduleAdapter(Listener listener) { this.listener = listener; }

    @SuppressLint("NotifyDataSetChanged") // Directory has structural header/state rows, refreshed together.
    public void submit(String doctorName, boolean validContext, List<DoctorSchedule> entries,
                       boolean loading, boolean writing, String busyId, DoctorScheduleRepository.Error error) {
        this.doctorName = doctorName;
        this.validContext = validContext;
        this.error = error;
        this.loading = loading;
        this.writing = writing;
        this.busyId = busyId;
        this.entries.clear();
        this.entries.addAll(entries);
        notifyDataSetChanged();
    }

    private boolean hasPlaceholder() {
        return !validContext || loading || error != DoctorScheduleRepository.Error.NONE || entries.isEmpty();
    }

    @Override public int getItemCount() { return 1 + (hasPlaceholder() ? 1 : entries.size()); }

    @Override public int getItemViewType(int position) {
        return position == 0 ? HEADER : hasPlaceholder() ? STATE : ENTRY;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (type == HEADER) return new HeaderHolder(ItemDoctorScheduleHeaderBinding.inflate(inflater, parent, false));
        if (type == STATE) return new StateHolder(ItemDoctorScheduleStateBinding.inflate(inflater, parent, false));
        return new EntryHolder(ItemDoctorScheduleBinding.inflate(inflater, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Context context = holder.itemView.getContext();
        if (holder instanceof HeaderHolder) {
            ItemDoctorScheduleHeaderBinding binding = ((HeaderHolder) holder).binding;
            binding.doctorHeading.setText(validContext ? context.getString(R.string.schedule_doctor_heading, doctorName)
                    : context.getString(R.string.schedule_context_unavailable));
            binding.addScheduleButton.setEnabled(validContext && !loading && !writing
                    && error == DoctorScheduleRepository.Error.NONE);
            binding.addScheduleButton.setOnClickListener(view -> listener.onAddSchedule());
            binding.refreshButton.setEnabled(!loading && !writing && validContext);
            binding.refreshButton.setOnClickListener(view -> listener.onRetrySchedules());
            binding.operationProgress.setVisibility(writing ? View.VISIBLE : View.GONE);
            binding.operationStatus.setVisibility(writing ? View.VISIBLE : View.GONE);
        } else if (holder instanceof StateHolder) {
            ItemDoctorScheduleStateBinding binding = ((StateHolder) holder).binding;
            boolean failed = !validContext || error != DoctorScheduleRepository.Error.NONE;
            boolean fetching = validContext && loading;
            binding.loadingIndicator.setVisibility(fetching ? View.VISIBLE : View.GONE);
            binding.stateIcon.setVisibility(fetching ? View.GONE : View.VISIBLE);
            binding.stateHeading.setText(fetching ? R.string.schedule_loading_heading
                    : failed ? R.string.schedule_error_heading : R.string.schedule_empty_heading);
            binding.stateDescription.setText(!validContext ? R.string.schedule_error_message
                    : fetching ? R.string.schedule_loading_message : failed
                    ? DoctorScheduleErrorMessages.forError(error) : R.string.schedule_empty_message);
            binding.retryButton.setVisibility(failed && !fetching ? View.VISIBLE : View.GONE);
            binding.retryButton.setText(validContext ? R.string.schedule_retry_button : R.string.schedule_back_to_doctors);
            binding.retryButton.setOnClickListener(view -> listener.onRetrySchedules());
        } else {
            bindEntry(((EntryHolder) holder).binding, entries.get(position - 1));
        }
    }

    private void bindEntry(ItemDoctorScheduleBinding binding, DoctorSchedule entry) {
        Context context = binding.getRoot().getContext();
        String day = DoctorScheduleUi.day(context, entry.getDayOfWeek());
        String range = context.getString(R.string.schedule_time_range,
                DoctorScheduleUi.time(context, entry.getStartMinute()), DoctorScheduleUi.time(context, entry.getEndMinute()));
        binding.dayName.setText(day);
        binding.timeRange.setText(range);
        binding.slotDuration.setText(context.getResources().getQuantityString(
                R.plurals.schedule_slot_display, entry.getSlotMinutes(), entry.getSlotMinutes()));
        binding.activeStatus.setText(entry.isActive() ? R.string.schedule_active_status : R.string.schedule_inactive_status);
        binding.activeStatus.setChipBackgroundColor(ColorStateList.valueOf(ContextCompat.getColor(context,
                entry.isActive() ? R.color.medibook_teal_container : R.color.medibook_background)));
        binding.activeStatus.setTextColor(ContextCompat.getColor(context,
                entry.isActive() ? R.color.medibook_teal_dark : R.color.medibook_text_secondary));
        binding.statusButton.setText(writing && entry.getId().equals(busyId) ? R.string.schedule_updating
                : entry.isActive() ? R.string.schedule_disable_button : R.string.schedule_enable_button);
        binding.editButton.setContentDescription(context.getString(R.string.schedule_edit_accessibility, day, range));
        binding.statusButton.setContentDescription(context.getString(entry.isActive()
                ? R.string.schedule_disable_accessibility : R.string.schedule_enable_accessibility, day, range));
        binding.editButton.setOnClickListener(view -> listener.onEditSchedule(entry));
        binding.statusButton.setOnClickListener(view -> listener.onToggleSchedule(entry));
        binding.editButton.setEnabled(!loading && !writing);
        binding.statusButton.setEnabled(!loading && !writing);
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final ItemDoctorScheduleHeaderBinding binding;
        HeaderHolder(ItemDoctorScheduleHeaderBinding binding) {
            super(binding.getRoot()); this.binding = binding;
            ViewCompat.setAccessibilityHeading(binding.doctorHeading, true);
        }
    }

    private static final class StateHolder extends RecyclerView.ViewHolder {
        final ItemDoctorScheduleStateBinding binding;
        StateHolder(ItemDoctorScheduleStateBinding binding) {
            super(binding.getRoot()); this.binding = binding;
            ViewCompat.setAccessibilityHeading(binding.stateHeading, true);
        }
    }

    private static final class EntryHolder extends RecyclerView.ViewHolder {
        final ItemDoctorScheduleBinding binding;
        EntryHolder(ItemDoctorScheduleBinding binding) {
            super(binding.getRoot()); this.binding = binding;
            ViewCompat.setAccessibilityHeading(binding.dayName, true);
        }
    }
}
