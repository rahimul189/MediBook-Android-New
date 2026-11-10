package com.example.medibook.activities.patient;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.medibook.R;
import com.example.medibook.activities.AppointmentSessionActivity;
import com.example.medibook.activities.AppointmentsActivity;
import com.example.medibook.databinding.ActivityBookAppointmentBinding;
import com.example.medibook.models.InventorySlot;
import com.example.medibook.models.UserProfile;
import com.example.medibook.repositories.AppointmentRepository;
import com.example.medibook.repositories.DoctorMapper;
import com.example.medibook.utils.AppointmentUi;
import com.example.medibook.utils.AuthUi;
import com.example.medibook.utils.DhakaDateTime;
import com.example.medibook.utils.PatientDoctorProfileContract;
import com.example.medibook.viewmodels.AppointmentOperationViewModel;
import com.example.medibook.adapters.AvailableSlotAdapter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class BookAppointmentActivity extends AppointmentSessionActivity {
    private ActivityBookAppointmentBinding binding;
    private final AppointmentRepository repository = new AppointmentRepository();
    private AppointmentOperationViewModel operation;
    private String doctorId;
    private long date;
    private InventorySlot selected;
    private AppointmentRepository.Availability availability;
    private boolean busy, observing;
    private androidx.appcompat.app.AlertDialog confirmation;
    private AvailableSlotAdapter slotsAdapter;
    private com.example.medibook.repositories.RealtimeDisplayRepository.Subscription liveQueue;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        binding = ActivityBookAppointmentBinding.inflate(getLayoutInflater()); setContentView(binding.getRoot());
        AuthUi.applyInsets(this, binding.getRoot());
        androidx.core.view.ViewCompat.setAccessibilityHeading(binding.doctorName, true);
        androidx.core.view.ViewCompat.setAccessibilityHeading(binding.availableTimesHeading, true);
        slotsAdapter = new AvailableSlotAdapter(slot -> {
            if (canAccess() && !busy) {
                selected = slot; binding.confirmButton.setEnabled(true);
                binding.selectedTime.setText(getString(R.string.appointment_selected_time,
                        DhakaDateTime.formatTime12Hour(slot.startMinute), DhakaDateTime.formatTime12Hour(slot.endMinute),
                        getString(R.string.next_slot, com.example.medibook.utils.SlotSerials.label(slot.slotSerial, slot.startMinute, slot.endMinute))));
                binding.selectedTime.setVisibility(View.VISIBLE);
            }
        });
        binding.slots.setLayoutManager(new LinearLayoutManager(this)); binding.slots.setAdapter(slotsAdapter);
        doctorId = getIntent().getStringExtra(PatientDoctorProfileContract.EXTRA_ID);
        date = state == null ? AppointmentUi.today() : state.getLong("date", AppointmentUi.today());
        operation = new ViewModelProvider(this).get(AppointmentOperationViewModel.class);
        if (operation.bookingId == null) operation.bookingId = state == null ? repository.newId() : state.getString("bookingId", repository.newId());
        binding.toolbar.setNavigationOnClickListener(view -> finish());
        binding.dateButton.setOnClickListener(view -> {
            if (!canAccess() || operation.operation != null) return;
            AppointmentUi.pickDate(this, date, (year, month, day) -> {
                if (!canAccess() || operation.operation != null) return;
                if (!DhakaDateTime.isDateBookable(year, month, day, System.currentTimeMillis())) {
                    binding.message.setText(R.string.appointment_past_date); return;
                }
                date = DhakaDateTime.localDateAtMidnight(year, month, day); refresh();
            });
        });
        binding.refreshButton.setOnClickListener(view -> { if (canAccess() && !busy) refresh(); });
        binding.confirmButton.setOnClickListener(view -> confirm());
    }
    @Override protected View protectedContent() { return binding.content; }
    @Override protected View sessionLoading() { return binding.sessionLoading.getRoot(); }
    @Override protected UserProfile.Role requiredRole() { return UserProfile.Role.PATIENT; }
    @Override protected void onVerifiedSession() {
        if (!DoctorMapper.isValidId(doctorId)) { binding.message.setText(R.string.appointment_invalid); return; }
        if (operation.operation != null) { setBusy(true); observeOperation(); }
        else refresh();
    }
    @Override protected void clearSessionViews() {
        if (liveQueue != null) liveQueue.close();
        liveQueue = null; binding.liveQueue.setText(null);
        binding.liveQueueTitle.setVisibility(View.GONE); binding.liveQueue.setVisibility(View.GONE);
        observing = false; selected = null; availability = null;
        binding.selectedTime.setText(null); binding.selectedTime.setVisibility(View.GONE);
        slotsAdapter.submit(java.util.Collections.emptyList()); binding.doctorName.setText(null); binding.message.setText(null);
        binding.fee.setText(null);
        binding.confirmButton.setEnabled(false);
        if (confirmation != null) confirmation.dismiss();
    }
    private void setBusy(boolean value) {
        busy = value; binding.progress.setVisibility(value ? View.VISIBLE : View.GONE);
        // A slow read for today's unpublished date must not prevent selecting a future date.
        binding.dateButton.setEnabled(operation.operation == null); binding.refreshButton.setEnabled(!value);
        binding.confirmButton.setEnabled(!value && selected != null);
        binding.confirmButton.setText(operation.operation != null ? R.string.appointment_working : R.string.appointment_confirm);
        slotsAdapter.setBusy(value);
    }
    private void refresh() {
        if (liveQueue != null) liveQueue.close();
        binding.liveQueueTitle.setVisibility(View.GONE); binding.liveQueue.setVisibility(View.GONE);
        binding.liveQueue.setText(R.string.live_connecting);
        liveQueue = com.example.medibook.repositories.RealtimeDisplayRepository.queue(owner, doctorId, date, (status, tokens) -> {
            if (canAccess()) {
                binding.liveQueueTitle.setVisibility(tokens.isEmpty() ? View.GONE : View.VISIBLE);
                binding.liveQueue.setVisibility(tokens.isEmpty() ? View.GONE : View.VISIBLE);
                binding.liveQueue.setText(status == R.string.live_queue_hint ? tokens : getString(R.string.live_queue_message, tokens, getString(status)));
            }
        });
        selected = null; binding.selectedTime.setText(null); binding.selectedTime.setVisibility(View.GONE);
        slotsAdapter.submit(java.util.Collections.emptyList()); setBusy(true);
        binding.dateButton.setText(getString(R.string.appointment_choose_date, AppointmentUi.date(date)));
        binding.message.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.medibook_text_secondary));
        binding.message.setText(R.string.appointment_loading);
        android.util.Log.d("MediBookAppointments", AppointmentUi.projectId() + " · " + AppointmentRepository.inventoryPath(doctorId, date));
        int version = ++requestVersion;
        repository.available(owner, doctorId, date).addOnCompleteListener(this, task -> {
            if (!canAccess() || version != requestVersion) return;
            setBusy(false);
            if (!task.isSuccessful()) { availability = null; binding.message.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.medibook_error)); binding.message.setText(AppointmentUi.errorText(this, task.getException())); return; }
            availability = task.getResult(); binding.doctorName.setText(availability.doctor.getName());
            binding.message.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.medibook_text_secondary));
            binding.message.setText(availability.emptyReason == null ? getString(R.string.appointment_booking_hint)
                    : getString(AppointmentUi.error(new AppointmentRepository.Failure(availability.emptyReason))));
            binding.fee.setText(getString(R.string.doctor_fee_amount, availability.doctor.getConsultationFee().toPlainString()));
            slotsAdapter.submit(availability.slots);
        });
    }
    private void confirm() {
        if (!canAccess() || busy || selected == null || availability == null || (confirmation != null && confirmation.isShowing())) return;
        InventorySlot slot = selected;
        com.example.medibook.models.Doctor shownDoctor = availability.doctor;
        if (slot.startAt.toDate().getTime() <= System.currentTimeMillis()) { refresh(); return; }
        confirmation = new MaterialAlertDialogBuilder(this).setTitle(R.string.appointment_confirm_title)
                .setMessage(getString(R.string.appointment_confirm_message, availability.doctor.getName(), AppointmentUi.date(date),
                        DhakaDateTime.formatTime12Hour(slot.startMinute), DhakaDateTime.formatTime12Hour(slot.endMinute),
                        availability.doctor.getConsultationFee().toPlainString(),
                        com.example.medibook.utils.SlotSerials.label(slot.slotSerial, slot.startMinute, slot.endMinute)))
                .setNegativeButton(R.string.cancel_button, null)
                .setPositiveButton(R.string.appointment_confirm, (dialog, which) -> {
                    if (!canAccess() || busy) return;
                    binding.message.setText(R.string.appointment_working);
                    operation.operation = repository.book(owner, operation.bookingId, slot, shownDoctor);
                    setBusy(true); observeOperation();
                }).show();
    }
    private void observeOperation() {
        if (observing || operation.operation == null) return;
        observing = true;
        operation.operation.addOnCompleteListener(this, task -> {
            if (!canAccess()) return;
            observing = false; operation.operation = null; setBusy(false);
            if (task.isSuccessful()) {
                // UI hint only. The destination resolves its summary from its authorized list,
                // never from an Intent-supplied patient/appointment snapshot or extra query.
                startActivity(new Intent(this, AppointmentsActivity.class)
                        .putExtra(AppointmentsActivity.EXTRA_BOOKING_RESULT, task.getResult().getId())); finish();
            } else {
                String error = AppointmentUi.errorText(this, task.getException());
                // Refresh removes stale availability; a persistent dialog keeps the write failure
                // visible even when the refresh finishes, rather than losing it in a short Snackbar.
                refresh();
                confirmation = new MaterialAlertDialogBuilder(this).setTitle(R.string.appointment_book)
                        .setMessage(error).setPositiveButton(android.R.string.ok, null).show();
            }
        });
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putLong("date", date); state.putString("bookingId", operation.bookingId); super.onSaveInstanceState(state);
    }
}
