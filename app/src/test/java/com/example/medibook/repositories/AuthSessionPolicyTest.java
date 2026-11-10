package com.example.medibook.repositories;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A verified Admin session must survive a quiet background revalidation, while an expired, removed,
 * mismatched or differently signed-in account must still end it. The policy is deliberately pure so
 * the decision can be asserted without Firebase.
 */
public class AuthSessionPolicyTest {
    private static boolean keeps(AuthRepository.Error error) {
        return AuthRepository.revalidationKeepsSession(true, true, true, error);
    }

    @Test public void validAdminSessionSurvivesTransientRevalidationFailures() {
        assertTrue(keeps(AuthRepository.Error.NETWORK));
        assertTrue(keeps(AuthRepository.Error.UNKNOWN));
        assertTrue(keeps(AuthRepository.Error.TOO_MANY_REQUESTS));
        assertTrue(keeps(AuthRepository.Error.AUTH_UNAVAILABLE));
        assertTrue(keeps(AuthRepository.Error.PROFILE_FAILED_ACCOUNT_REMAINS));
        // A denied profile read is a rules/propagation problem, not a revoked account. The server
        // still denies every operation, so the screen may stay but nothing is granted.
        assertTrue(keeps(AuthRepository.Error.PERMISSION_DENIED));
    }

    @Test public void staleOrInvalidSessionsAreStillEnded() {
        assertFalse(keeps(AuthRepository.Error.INVALID_CREDENTIALS));
        assertFalse(keeps(AuthRepository.Error.SESSION_EXPIRED));
        assertFalse(keeps(AuthRepository.Error.PROFILE_MISSING));
        assertFalse(keeps(AuthRepository.Error.INVALID_PROFILE));
        assertFalse(keeps(AuthRepository.Error.ROLE_MISMATCH));
    }

    @Test public void aColdCheckOrAnotherAccountNeverReusesTheSession() {
        assertFalse(AuthRepository.revalidationKeepsSession(false, true, true,
                AuthRepository.Error.NETWORK));
        assertFalse(AuthRepository.revalidationKeepsSession(true, false, true,
                AuthRepository.Error.NETWORK));
        assertFalse(AuthRepository.revalidationKeepsSession(true, true, false,
                AuthRepository.Error.NETWORK));
        assertFalse(AuthRepository.revalidationKeepsSession(true, true, true, null));
    }
}
