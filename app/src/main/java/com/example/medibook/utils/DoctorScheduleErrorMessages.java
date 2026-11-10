package com.example.medibook.utils;

import com.example.medibook.R;
import com.example.medibook.repositories.DoctorScheduleRepository;

/** No raw SDK exception or identifier is displayed or logged. */
public final class DoctorScheduleErrorMessages {
    private DoctorScheduleErrorMessages() { }

    public static int forError(DoctorScheduleRepository.Error error) {
        switch (error) {
            case NETWORK: return R.string.schedule_error_network;
            case PERMISSION: return R.string.schedule_error_permission;
            case NOT_FOUND: return R.string.schedule_error_not_found;
            case PARENT_MISSING: return R.string.schedule_error_parent;
            case CONFLICT: return R.string.schedule_error_conflict;
            case OVERLAP: return R.string.schedule_error_overlap;
            case ALREADY_EXISTS: return R.string.schedule_error_exists;
            case INVALID_DATA: return R.string.schedule_error_data;
            case SESSION_CHANGED: return R.string.schedule_error_session;
            case OUTCOME_UNKNOWN: return R.string.schedule_error_outcome;
            default: return R.string.schedule_error_unknown;
        }
    }
}
