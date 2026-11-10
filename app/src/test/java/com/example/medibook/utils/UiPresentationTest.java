package com.example.medibook.utils;

import static org.junit.Assert.*;
import com.example.medibook.R;
import com.example.medibook.repositories.AuthRepository;
import com.example.medibook.wellness.WellnessCards;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class UiPresentationTest {
    @Test public void credentialFailuresAreNonEnumerating() {
        for (String code : new String[]{"ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_LOGIN_CREDENTIALS",
                "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND", "ERROR_INVALID_EMAIL"}) {
            assertEquals(AuthRepository.Error.INVALID_CREDENTIALS, AuthFailurePresentation.forCode(code));
        }
    }

    @Test public void loginOnlyOffersGoogleForInvalidCredentials() {
        assertEquals(R.string.login_error_credentials, AuthErrorMessages.forLoginError(AuthRepository.Error.INVALID_CREDENTIALS));
        for (AuthRepository.Error error : AuthRepository.Error.values()) {
            if (error != AuthRepository.Error.INVALID_CREDENTIALS) {
                assertNotEquals(error.name(), R.string.login_error_credentials, AuthErrorMessages.forLoginError(error));
                assertEquals(AuthErrorMessages.forError(error), AuthErrorMessages.forLoginError(error));
            }
        }
    }

    @Test public void linkingAndRegistrationKeepTheirOriginalCredentialMessage() {
        assertEquals(R.string.auth_error_credentials, AuthErrorMessages.forError(AuthRepository.Error.INVALID_CREDENTIALS));
        assertEquals(R.string.google_error_reauth, AuthErrorMessages.forLoginError(AuthRepository.Error.REAUTH_REQUIRED));
    }

    @Test public void disabledAndConfigurationFailuresDoNotSuggestProviderBypass() {
        assertEquals(AuthRepository.Error.ACCOUNT_DISABLED, AuthFailurePresentation.forCode("ERROR_USER_DISABLED"));
        assertEquals(R.string.auth_error_disabled, AuthErrorMessages.forLoginError(AuthRepository.Error.ACCOUNT_DISABLED));
        assertEquals(AuthRepository.Error.AUTH_UNAVAILABLE, AuthFailurePresentation.forCode("ERROR_OPERATION_NOT_ALLOWED"));
        assertEquals(AuthRepository.Error.AUTH_CONFIGURATION, AuthFailurePresentation.forCode("ERROR_INVALID_API_KEY"));
        assertEquals(AuthRepository.Error.AUTH_CONFIGURATION, AuthFailurePresentation.forCode("ERROR_APP_NOT_AUTHORIZED"));
    }

    @Test public void expiredTokensAndUnknownCodesHaveSeparateSafeCopy() {
        assertEquals(AuthRepository.Error.SESSION_EXPIRED, AuthFailurePresentation.forCode("ERROR_USER_TOKEN_EXPIRED"));
        assertEquals(AuthRepository.Error.SESSION_EXPIRED, AuthFailurePresentation.forCode("ERROR_INVALID_USER_TOKEN"));
        assertEquals(AuthRepository.Error.AUTH_REJECTED, AuthFailurePresentation.forCode("unexpected"));
        assertEquals(AuthRepository.Error.AUTH_REJECTED, AuthFailurePresentation.forCode(null));
    }

    @Test public void newDisplayCategoriesRetainOriginalCredentialSessionDenial() {
        for (AuthRepository.Error error : new AuthRepository.Error[]{AuthRepository.Error.ACCOUNT_DISABLED,
                AuthRepository.Error.AUTH_CONFIGURATION, AuthRepository.Error.AUTH_REJECTED}) {
            assertFalse(AuthRepository.revalidationKeepsSession(true, true, true, error));
        }
    }

    @Test public void networkAndRateLimitCopyIsNotChanged() {
        assertEquals(R.string.auth_error_network, AuthErrorMessages.forLoginError(AuthRepository.Error.NETWORK));
        assertEquals(R.string.auth_error_rate_limit, AuthErrorMessages.forLoginError(AuthRepository.Error.TOO_MANY_REQUESTS));
        assertEquals(R.string.google_error_configuration, AuthErrorMessages.forLoginError(AuthRepository.Error.GOOGLE_CONFIGURATION));
    }

    @Test public void statusColorsUseExistingDisplayStatesWithoutInventingStatuses() {
        assertEquals(R.color.medibook_blue, AppointmentPresentation.foreground(AppointmentDisplayStatus.CONFIRMED));
        assertEquals(R.color.medibook_error, AppointmentPresentation.foreground(AppointmentDisplayStatus.CANCELLED));
        assertNotEquals(AppointmentPresentation.background(AppointmentDisplayStatus.CONFIRMED),
                AppointmentPresentation.background(AppointmentDisplayStatus.CANCELLED));
        assertNotEquals(AppointmentPresentation.background(AppointmentDisplayStatus.CONFIRMED),
                AppointmentPresentation.background(AppointmentDisplayStatus.COMPLETED));
    }

    @Test public void everyOriginalWellnessCardHasItsOwnTextEquivalent() {
        Set<Integer> descriptions = new HashSet<>();
        for (int index = 0; index < WellnessCards.count(); index++) {
            assertNotEquals(0, WellnessCards.description(index));
            assertTrue(descriptions.add(WellnessCards.description(index)));
        }
        assertEquals(11, descriptions.size());
        assertEquals(R.string.wellness_hydrated_text, WellnessCards.description(0));
        assertEquals(R.string.wellness_reminders_text, WellnessCards.description(10));
    }
}
