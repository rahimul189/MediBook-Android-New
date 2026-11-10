package com.example.medibook.utils;

/** In-memory reuse of a previously server-verified UID/claims/profile; never persisted privileges. */
public final class VerifiedSessionLease {
    public static final long MAX_AGE_MILLIS = 120_000L;
    private VerifiedSessionLease() { }
    public static boolean reusable(String verifiedUid, String currentUid, long verifiedAt, long elapsedNow,
                                   long tokenExpiresAt, long wallNow) {
        long age = elapsedNow - verifiedAt;
        return verifiedUid != null && verifiedUid.equals(currentUid) && verifiedAt >= 0
                && age >= 0 && age < MAX_AGE_MILLIS && tokenExpiresAt - wallNow > 30_000L;
    }
}
