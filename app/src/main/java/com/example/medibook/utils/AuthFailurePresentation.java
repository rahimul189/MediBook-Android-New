package com.example.medibook.utils;

import com.example.medibook.repositories.AuthRepository;

/** Safe display categories only. Never inspects exception text, emails or account existence. */
public final class AuthFailurePresentation {
    private AuthFailurePresentation() { }

    public static AuthRepository.Error forCode(String code) {
        if (code == null) return AuthRepository.Error.AUTH_REJECTED;
        switch (code) {
            // Deliberately indistinguishable: never disclose whether an email is registered.
            case "ERROR_INVALID_CREDENTIAL":
            case "ERROR_INVALID_LOGIN_CREDENTIALS":
            case "ERROR_WRONG_PASSWORD":
            case "ERROR_USER_NOT_FOUND":
            case "ERROR_INVALID_EMAIL":
                return AuthRepository.Error.INVALID_CREDENTIALS;
            case "ERROR_USER_TOKEN_EXPIRED":
            case "ERROR_INVALID_USER_TOKEN":
                return AuthRepository.Error.SESSION_EXPIRED;
            case "ERROR_USER_DISABLED": return AuthRepository.Error.ACCOUNT_DISABLED;
            case "ERROR_OPERATION_NOT_ALLOWED": return AuthRepository.Error.AUTH_UNAVAILABLE;
            case "ERROR_APP_NOT_AUTHORIZED":
            case "ERROR_INVALID_API_KEY":
            case "ERROR_INVALID_APP_CREDENTIAL":
                return AuthRepository.Error.AUTH_CONFIGURATION;
            default: return AuthRepository.Error.AUTH_REJECTED;
        }
    }
}
