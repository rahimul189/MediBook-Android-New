package com.example.medibook.utils;

import com.example.medibook.R;
import com.example.medibook.repositories.DoctorRepository;

/** Safe messages only; raw SDK exceptions and record data never enter logs or error labels. */
public final class DoctorErrorMessages {
    private DoctorErrorMessages() { }

    public static int forError(DoctorRepository.Error error) {
        switch (error) {
            case NETWORK: return R.string.doctor_error_network;
            case PERMISSION: return R.string.doctor_error_permission;
            case NOT_FOUND: return R.string.doctor_error_not_found;
            case CONFLICT: return R.string.doctor_error_conflict;
            case ALREADY_EXISTS: return R.string.doctor_error_already_exists;
            case INVALID_DATA: return R.string.doctor_error_data;
            case SESSION_CHANGED: return R.string.doctor_error_session;
            case OUTCOME_UNKNOWN: return R.string.doctor_error_outcome;
            default: return R.string.doctor_error_unknown;
        }
    }
}
