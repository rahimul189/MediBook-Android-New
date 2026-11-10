package com.example.medibook.utils;

import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.example.medibook.R;
import com.example.medibook.models.Appointment;
import com.google.android.material.chip.Chip;

/** Presentation only: delegates every status decision to the existing appointment display policy. */
public final class AppointmentPresentation {
    private AppointmentPresentation() { }

    /** Keep the row in place while unresolved, and do not reassign unchanged text on clock ticks. */
    public static void bindSlot(TextView view, Appointment appointment) {
        String text = view.getContext().getString(R.string.next_slot, SlotSerials.label(
                appointment.getSlotSerial(), appointment.getStartMinute(), appointment.getEndMinute()));
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
    }

    public static int foreground(AppointmentDisplayStatus status) {
        switch (status) {
            case CANCELLED: return R.color.medibook_error;
            case COMPLETED: return R.color.medibook_success;
            default: return R.color.medibook_blue;
        }
    }

    public static int background(AppointmentDisplayStatus status) {
        switch (status) {
            case CANCELLED: return R.color.medibook_error_container;
            case COMPLETED: return R.color.medibook_success_container;
            default: return R.color.medibook_blue_container;
        }
    }

    public static void bindStatus(Chip chip, Appointment appointment) {
        AppointmentDisplayStatus status = AppointmentDisplayStatus.of(appointment, System.currentTimeMillis());
        ViewUpdates.text(chip, AppointmentUi.status(chip.getContext(), appointment));
        int foreground = ContextCompat.getColor(chip.getContext(), foreground(status));
        int background = ContextCompat.getColor(chip.getContext(), background(status));
        if (chip.getCurrentTextColor() != foreground) chip.setTextColor(foreground);
        if (chip.getChipBackgroundColor() == null || chip.getChipBackgroundColor().getDefaultColor() != background) {
            chip.setChipBackgroundColor(ColorStateList.valueOf(background));
        }
    }
}
