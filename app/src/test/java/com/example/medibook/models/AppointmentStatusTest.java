package com.example.medibook.models;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Pure status tests; no Android, Firestore, session or repository dependencies. */
public class AppointmentStatusTest {
    private static final AppointmentStatus[] ALL = AppointmentStatus.values();
    private static final UserProfile.Role[] ROLES = {
            UserProfile.Role.PATIENT, UserProfile.Role.ADMIN
    };

    @Test
    public void wireValuesAreExactAndUnique() {
        assertEquals(2, ALL.length);
        assertEquals("Confirmed", AppointmentStatus.CONFIRMED.getWireValue());
        assertEquals("Cancelled", AppointmentStatus.CANCELLED.getWireValue());

        Set<String> values = new HashSet<>();
        for (AppointmentStatus status : ALL) assertTrue(values.add(status.getWireValue()));
        assertEquals(2, values.size());
    }

    @Test
    public void newBookingsStartConfirmedWithoutAnApprovalStep() {
        assertSame(AppointmentStatus.CONFIRMED, AppointmentStatus.initial());
        assertFalse(AppointmentStatus.initial().isTerminal());
    }

    @Test
    public void fromWireAcceptsEveryExactValue() {
        for (AppointmentStatus status : ALL) {
            assertSame(status, AppointmentStatus.fromWire(status.getWireValue()));
        }
    }

    @Test
    public void fromWireRejectsNullObsoleteUnknownMalformedAndWrongCase() {
        String[] invalid = {
                null, "", " ", "confirmed", "CONFIRMED", "Confirmed ", " Confirmed",
                "cancelled", "CANCELLED", "Cancelled!",
                "Pending", "pending", "PENDING", "Pending ",
                "Completed", "completed", "COMPLETED",
                "Unknown", "0"
        };
        for (String value : invalid) {
            assertThrows(value, IllegalArgumentException.class, () -> AppointmentStatus.fromWire(value));
        }
    }

    @Test
    public void patientMayOnlyCancelAConfirmedAppointment() {
        for (AppointmentStatus from : ALL) {
            for (AppointmentStatus target : ALL) {
                boolean expected = from == AppointmentStatus.CONFIRMED
                        && target == AppointmentStatus.CANCELLED;
                assertEquals(from + " -> " + target,
                        expected, from.canTransitionTo(UserProfile.Role.PATIENT, target));
            }
        }
    }

    @Test
    public void adminMayOnlyCancelAConfirmedAppointment() {
        for (AppointmentStatus from : ALL) {
            for (AppointmentStatus target : ALL) {
                boolean expected = from == AppointmentStatus.CONFIRMED
                        && target == AppointmentStatus.CANCELLED;
                assertEquals(from + " -> " + target,
                        expected, from.canTransitionTo(UserProfile.Role.ADMIN, target));
            }
        }
    }

    @Test
    public void sameStatusTransitionsAreRejectedForEveryRole() {
        for (UserProfile.Role role : ROLES) {
            for (AppointmentStatus status : ALL) {
                assertFalse(status + " -> " + status,
                        status.canTransitionTo(role, status));
            }
        }
    }

    @Test
    public void cancelledIsTheOnlyTerminalPersistedStatus() {
        assertTrue(AppointmentStatus.CANCELLED.isTerminal());
        for (UserProfile.Role role : ROLES) {
            for (AppointmentStatus target : ALL) {
                assertFalse("Cancelled -> " + target,
                        AppointmentStatus.CANCELLED.canTransitionTo(role, target));
            }
        }
        assertFalse(AppointmentStatus.CONFIRMED.isTerminal());
    }

    @Test
    public void bothRolesCanCancelAndNoTransitionCreatesCompletion() {
        for (UserProfile.Role role : ROLES) {
            assertTrue(AppointmentStatus.CONFIRMED.canTransitionTo(
                    role, AppointmentStatus.CANCELLED));
            // There is no persisted COMPLETED target to transition to; the enum deliberately
            // exposes only Confirmed and Cancelled.
            assertEquals(2, ALL.length);
        }
    }

    @Test
    public void nullRoleOrTargetIsRejected() {
        assertFalse(AppointmentStatus.CONFIRMED.canTransitionTo(
                null, AppointmentStatus.CANCELLED));
        assertFalse(AppointmentStatus.CONFIRMED.canTransitionTo(
                UserProfile.Role.PATIENT, null));
        assertFalse(AppointmentStatus.CONFIRMED.canTransitionTo(null, null));
    }
}
