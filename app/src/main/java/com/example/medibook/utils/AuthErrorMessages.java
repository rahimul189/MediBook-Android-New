package com.example.medibook.utils;

import androidx.annotation.StringRes;

import com.example.medibook.R;
import com.example.medibook.repositories.AuthRepository;

/** Maps safe error categories to display text without exposing Firebase exception details. */
public final class AuthErrorMessages {

    private AuthErrorMessages() {
    }

    @StringRes
    public static int forLoginError(AuthRepository.Error error) {
        return error == AuthRepository.Error.INVALID_CREDENTIALS
                ? R.string.login_error_credentials : forError(error);
    }

    @StringRes
    public static int forError(AuthRepository.Error error) {
        switch (error) {
            case GOOGLE_CONFIGURATION: return R.string.google_error_configuration;
            case GOOGLE_NO_CREDENTIAL: return R.string.google_error_no_credential;
            case GOOGLE_INVALID_CREDENTIAL: return R.string.google_error_invalid;
            case GOOGLE_UNAVAILABLE: return R.string.google_error_unavailable;
            case GOOGLE_CONFLICT: return R.string.google_error_conflict;
            case GOOGLE_EMAIL_MISMATCH: return R.string.google_error_email_mismatch;
            case GOOGLE_ALREADY_LINKED: return R.string.google_already_linked;
            case GOOGLE_CANCELLED: return R.string.google_cancelled;
            case REAUTH_REQUIRED: return R.string.google_error_reauth;
            case AUTH_IN_PROGRESS: return R.string.google_error_draining;
            case NETWORK: return R.string.auth_error_network;
            case SESSION_EXPIRED: return R.string.appointment_session_changed;
            case TOO_MANY_REQUESTS: return R.string.auth_error_rate_limit;
            case EMAIL_IN_USE: return R.string.auth_error_email_in_use;
            case WEAK_PASSWORD: return R.string.auth_error_password_policy;
            case INVALID_CREDENTIALS: return R.string.auth_error_credentials;
            case ACCOUNT_DISABLED: return R.string.auth_error_disabled;
            case AUTH_CONFIGURATION: return R.string.auth_error_configuration;
            case AUTH_REJECTED: return R.string.auth_error_rejected;
            case AUTH_UNAVAILABLE: return R.string.auth_error_unavailable;
            case PERMISSION_DENIED: return R.string.auth_error_permission;
            case PROFILE_MISSING: return R.string.auth_error_profile_missing;
            case INVALID_PROFILE: return R.string.auth_error_profile_invalid;
            case ROLE_MISMATCH: return R.string.auth_error_role_mismatch;
            case PROFILE_FAILED_ACCOUNT_REMOVED: return R.string.auth_error_profile_rolled_back;
            case PROFILE_FAILED_ACCOUNT_REMAINS: return R.string.auth_error_profile_cleanup_failed;
            default: return R.string.auth_error_unknown;
        }
    }
}
