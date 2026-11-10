package com.example.medibook.utils;

import org.junit.Test;
import static org.junit.Assert.*;

public class VerifiedSessionLeaseTest {
    private boolean reuse(String verified, String current, long saved, long elapsed, long expiry, long wall) {
        return VerifiedSessionLease.reusable(verified, current, saved, elapsed, expiry, wall);
    }
    @Test public void sameVerifiedUidCanNavigateWithinTheShortLease() {
        assertTrue(reuse("patient", "patient", 1000, 2000, 900000, 100000));
        assertTrue(reuse("admin", "admin", 1000, 120999, 900000, 100000));
    }
    @Test public void newAccountCannotReuseAnotherAccountsVerifiedRole() {
        assertFalse(reuse("admin", "patient", 1000, 2000, 900000, 100000));
        assertFalse(reuse("patient", "admin", 1000, 2000, 900000, 100000));
    }
    @Test public void logoutOrTokenInvalidationRequiresReverification() {
        assertFalse(reuse(null, "admin", -1, 2000, 900000, 100000));
        assertFalse(reuse("admin", null, 1000, 2000, 900000, 100000));
    }
    @Test public void expiredLeaseMustRefreshClaimsAndServerProfile() {
        assertFalse(reuse("admin", "admin", 1000, 121000, 900000, 100000));
        assertFalse(reuse("admin", "admin", 1000, 500000, 900000, 100000));
    }
    @Test public void expiredOrNearlyExpiredSdkTokenCannotReuseRole() {
        assertFalse(reuse("admin", "admin", 1000, 2000, 100000, 100000));
        assertFalse(reuse("admin", "admin", 1000, 2000, 130000, 100000));
    }
    @Test public void monotonicClockResetOrNoVerifiedTimestampRequiresRefresh() {
        assertFalse(reuse("admin", "admin", 5000, 1000, 900000, 100000));
        assertFalse(reuse("admin", "admin", -1, 2000, 900000, 100000));
    }
}
