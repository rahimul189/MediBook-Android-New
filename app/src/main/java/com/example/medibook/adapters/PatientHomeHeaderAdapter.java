package com.example.medibook.adapters;

import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.example.medibook.R;
import com.example.medibook.databinding.ItemPatientDirectoryHeaderBinding;
import com.example.medibook.models.Appointment;
import com.example.medibook.repositories.DashboardState;
import com.example.medibook.utils.AppointmentUi;
import com.example.medibook.utils.DhakaDateTime;
import com.example.medibook.viewmodels.PatientDoctorDirectoryViewModel;
import com.example.medibook.wellness.HealthWellnessCarousel;

import java.util.ArrayList;
import java.util.List;

/** A separate ConcatAdapter header keeps search focus stable when only result rows change. */
public final class PatientHomeHeaderAdapter extends RecyclerView.Adapter<PatientHomeHeaderAdapter.Holder> {
    public interface Listener {
        void onSearchChanged(String query);
        void onSpecializationChanged(String specialization);
        void onSearchSubmitted();
        void onOpenAppointments();
        void onCallHospital();
        void onOpenEmergencyContact();
        void onOpenAirQuality();
        void onViewNextAppointment();
        void onRetryNextAppointment();
    }

    private final Listener listener;
    private PatientDoctorDirectoryViewModel.State state;
    private String patientName;
    private boolean handlingInput;
    private Holder bound;
    private int carouselPage;
    private boolean carouselActive;
    private Appointment nextAppointment;
    private DashboardState nextState = DashboardState.LOADING;

    public void setNextAppointment(Appointment value, DashboardState state) {
        boolean same = nextState == state && sameNext(nextAppointment, value);
        nextAppointment = value; nextState = state;
        if (!same && bound != null) bound.renderNextAppointment();
    }

    private static boolean sameNext(Appointment a, Appointment b) {
        if (a == null || b == null) return a == b;
        return com.example.medibook.utils.AppointmentSerials.sameSlot(a, b)
                && a.getDoctorName().equals(b.getDoctorName()) && a.getDoctorSpecialization().equals(b.getDoctorSpecialization())
                && a.getStatus() == b.getStatus() && a.getSlotSerial() == b.getSlotSerial();
    }

    public PatientHomeHeaderAdapter(Listener listener, PatientDoctorDirectoryViewModel.State initial) {
        this.listener = listener;
        state = initial;
    }

    public void submit(PatientDoctorDirectoryViewModel.State next) {
        state = next;
        if (bound != null) {
            if (handlingInput) bound.renderSummary();
            else bound.bind(); // No RecyclerView change animation/detach for the static header.
        }
    }

    public void setPatientName(String name) {
        if (TextUtils.equals(patientName, name)) return;
        patientName = name;
        if (bound != null) bound.renderGreeting();
    }

    /** Restores the Health &amp; Wellness card across Activity recreation. */
    public void setCarouselPage(int page) {
        carouselPage = Math.max(0, page);
        if (bound != null) bound.carousel.select(carouselPage);
    }

    public int getCarouselPage() { return carouselPage; }

    /** Auto-slide runs only while the dashboard Activity is started. */
    public void setCarouselActive(boolean active) {
        carouselActive = active;
        if (bound == null) return;
        if (active) bound.carousel.start();
        else bound.carousel.stop();
    }

    public void releaseCarousel() {
        if (bound != null) bound.carousel.release();
    }

    public void closeMenus() {
        if (bound != null) {
            bound.binding.specializationInput.dismissDropDown();
            bound.binding.searchInput.clearFocus();
        }
    }

    @Override public int getItemCount() { return 1; }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(ItemPatientDirectoryHeaderBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) { bound = holder; holder.bind(); }

    @Override public void onViewRecycled(@NonNull Holder holder) {
        holder.binding.specializationInput.dismissDropDown();
        if (bound == holder) bound = null;
        super.onViewRecycled(holder);
    }

    public final class Holder extends RecyclerView.ViewHolder {
        private final ItemPatientDirectoryHeaderBinding binding;
        private final HealthWellnessCarousel carousel;
        private boolean rendering;
        private List<String> options = new ArrayList<>();
        private String[] specializationLabels;

        Holder(ItemPatientDirectoryHeaderBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            // Cards are informational images only: the carousel attaches no click/long-click listener.
            carousel = new HealthWellnessCarousel(binding.healthCarousel, binding.healthCarouselDots,
                    binding.healthCarouselCounter);
            carousel.setPageListener(page -> carouselPage = page);
            carousel.select(carouselPage);
            if (carouselActive) carousel.start();
            ViewCompat.setAccessibilityHeading(binding.welcomeMessage, true);
            ViewCompat.setAccessibilityHeading(binding.wellnessHeading, true);
            ViewCompat.setAccessibilityHeading(binding.directoryHeading, true);
            ViewCompat.setAccessibilityHeading(binding.nextHeading, true);
            binding.nextDetailsButton.setOnClickListener(view -> listener.onViewNextAppointment());
            binding.nextRetryButton.setOnClickListener(view -> listener.onRetryNextAppointment());
            binding.appointmentsButton.setOnClickListener(view -> listener.onOpenAppointments());
            binding.hospitalButton.setOnClickListener(view -> listener.onCallHospital());
            binding.contactButton.setOnClickListener(view -> listener.onOpenEmergencyContact());
            binding.airQualityButton.setOnClickListener(view -> listener.onOpenAirQuality());
            binding.searchInput.setOnEditorActionListener((view, action, event) -> {
                if (action != EditorInfo.IME_ACTION_SEARCH) return false;
                view.clearFocus();
                listener.onSearchSubmitted();
                return true;
            });
            binding.searchInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                    if (rendering) return;
                    handlingInput = true;
                    try { listener.onSearchChanged(text.toString()); }
                    finally { handlingInput = false; }
                }
                @Override public void afterTextChanged(Editable text) { }
            });
            binding.specializationInput.setOnItemClickListener((parent, view, position, id) -> {
                if (rendering || position < 0 || position > options.size()) return;
                handlingInput = true;
                try { listener.onSpecializationChanged(position == 0 ? "" : options.get(position - 1)); }
                finally { handlingInput = false; }
            });
        }

        void bind() {
            rendering = true;
            try {
                renderGreeting();
                renderNextAppointment();
                String current = binding.searchInput.getText() == null ? "" : binding.searchInput.getText().toString();
                if (!current.equals(state.query)) binding.searchInput.setText(state.query);
                if (specializationLabels == null || !options.equals(state.specializations)) {
                    options = state.specializations;
                    specializationLabels = new String[options.size() + 1];
                    specializationLabels[0] = itemView.getContext().getString(R.string.patient_specialization_all);
                    for (int index = 0; index < options.size(); index++) specializationLabels[index + 1] = options.get(index);
                    binding.specializationInput.setSimpleItems(specializationLabels);
                }
                String selected = state.specialization.isEmpty() ? specializationLabels[0] : state.specialization;
                if (!TextUtils.equals(binding.specializationInput.getText(), selected)) binding.specializationInput.setText(selected, false);
                binding.specializationLayout.setEnabled(!options.isEmpty()
                        && state.status == PatientDoctorDirectoryViewModel.Status.READY);
                boolean loadingSpecializations = options.isEmpty()
                        && state.status == PatientDoctorDirectoryViewModel.Status.LOADING;
                binding.specializationLayout.setHelperText(options.isEmpty()
                        ? itemView.getContext().getString(loadingSpecializations
                        ? R.string.patient_specialization_loading : R.string.patient_specialization_pending) : null);
                binding.searchLayout.setEnabled(state.status != PatientDoctorDirectoryViewModel.Status.LOADING);
                renderSummary();
            } finally { rendering = false; }
        }

        void renderGreeting() {
            binding.welcomeMessage.setVisibility(patientName == null ? View.GONE : View.VISIBLE);
            CharSequence text = patientName == null ? null : itemView.getContext().getString(R.string.patient_welcome, patientName);
            if (!TextUtils.equals(binding.welcomeMessage.getText(), text)) binding.welcomeMessage.setText(text);
        }

        void renderSummary() {
            boolean ready = state.status == PatientDoctorDirectoryViewModel.Status.READY;
            binding.resultCount.setVisibility(ready ? View.VISIBLE : View.GONE);
            if (ready) binding.resultCount.setText(itemView.getContext().getResources().getQuantityString(
                    R.plurals.patient_directory_result_count, state.doctors.size(), state.doctors.size()));
        }

        void renderNextAppointment() {
            Appointment value = nextAppointment;
            boolean show = value != null && value.getStartAt().toDate().getTime() > System.currentTimeMillis();
            binding.nextInformation.setVisibility(show ? View.VISIBLE : View.GONE);
            binding.nextDetailsButton.setVisibility(show ? View.VISIBLE : View.GONE);
            binding.nextRetryButton.setVisibility(nextState == DashboardState.ERROR || nextState == DashboardState.CACHED ? View.VISIBLE : View.GONE);
            int message = nextState == DashboardState.LOADING ? R.string.dashboard_loading
                    : nextState == DashboardState.ERROR ? R.string.dashboard_error
                    : nextState == DashboardState.CACHED ? R.string.dashboard_cached
                    : !show ? R.string.next_appointment_empty
                    : nextState == DashboardState.FIRESTORE ? R.string.dashboard_firestore : R.string.dashboard_current;
            binding.nextMessage.setText(message);
            binding.nextMessage.setTextColor(androidx.core.content.ContextCompat.getColor(itemView.getContext(),
                    nextState == DashboardState.ERROR ? R.color.medibook_error : R.color.medibook_text_secondary));
            if (!show) return;
            binding.nextDoctor.setText(value.getDoctorName()); binding.nextSpecialty.setText(value.getDoctorSpecialization());
            binding.nextDate.setText(AppointmentUi.date(value.getAppointmentDate().toDate().getTime()));
            binding.nextTime.setText(itemView.getContext().getString(R.string.next_time_range,
                    DhakaDateTime.formatTime12Hour(value.getStartMinute()), DhakaDateTime.formatTime12Hour(value.getEndMinute())));
            com.example.medibook.utils.AppointmentPresentation.bindSlot(binding.nextSlot, value);
            if (nextState == DashboardState.CACHED) {
                binding.nextStatus.setText(R.string.dashboard_verifying_status);
                binding.nextStatus.setTextColor(androidx.core.content.ContextCompat.getColor(itemView.getContext(), R.color.medibook_warning));
                binding.nextStatus.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(itemView.getContext(), R.color.medibook_warning_container)));
            } else com.example.medibook.utils.AppointmentPresentation.bindStatus(binding.nextStatus, value);
        }

    }
}
