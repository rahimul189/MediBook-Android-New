package com.example.medibook.viewmodels;

import androidx.lifecycle.ViewModel;

import com.example.medibook.models.Appointment;
import com.google.android.gms.tasks.Task;

/** Holds only the pending operation/identity across rotation, never an Activity or View. */
public final class AppointmentOperationViewModel extends ViewModel {
    public String bookingId;
    public Task<Appointment> operation;
}
