package com.example.medibook.utils;

import com.example.medibook.models.DoctorSchedule;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/** Half-open weekly windows; these tests do not claim collection-wide concurrency exclusion. */
public class DoctorScheduleConflictsTest {
    private DoctorSchedule entry(String id, int day, int start, int end) {
        return new DoctorSchedule(id, day, start, end, 15, true);
    }

    @Test
    public void emptyDirectoryHasNoConflict() {
        assertFalse(DoctorScheduleConflicts.overlaps(entry("new", 1, 540, 600), Collections.emptyList()));
    }

    @Test
    public void identicalWindowsWithDifferentIdsOverlap() {
        assertTrue(DoctorScheduleConflicts.overlaps(entry("new", 1, 540, 600),
                Collections.singletonList(entry("other", 1, 540, 600))));
    }

    @Test
    public void partialOverlapIsDetectedInBothDirections() {
        DoctorSchedule earlier = entry("earlier", 1, 540, 600);
        DoctorSchedule later = entry("later", 1, 570, 630);
        assertTrue(DoctorScheduleConflicts.overlaps(earlier, Collections.singletonList(later)));
        assertTrue(DoctorScheduleConflicts.overlaps(later, Collections.singletonList(earlier)));
    }

    @Test
    public void containmentIsDetectedInBothDirections() {
        DoctorSchedule outer = entry("outer", 1, 540, 720);
        DoctorSchedule inner = entry("inner", 1, 570, 600);
        assertTrue(DoctorScheduleConflicts.overlaps(outer, Collections.singletonList(inner)));
        assertTrue(DoctorScheduleConflicts.overlaps(inner, Collections.singletonList(outer)));
    }

    @Test
    public void touchingEndpointsAreAllowed() {
        DoctorSchedule candidate = entry("new", 1, 600, 660);
        assertFalse(DoctorScheduleConflicts.overlaps(candidate, Arrays.asList(
                entry("before", 1, 540, 600), entry("after", 1, 660, 720))));
    }

    @Test
    public void differentWeekdaysDoNotConflict() {
        assertFalse(DoctorScheduleConflicts.overlaps(entry("new", 1, 540, 600),
                Collections.singletonList(entry("other", 2, 540, 600))));
    }

    @Test
    public void inactiveExistingWindowsAreIgnored() {
        assertFalse(DoctorScheduleConflicts.overlaps(entry("new", 1, 540, 600),
                Collections.singletonList(entry("other", 1, 540, 600).withActive(false))));
    }

    @Test
    public void inactiveCandidateMayBeSavedButEnablingChecksConflicts() {
        DoctorSchedule candidate = entry("new", 1, 540, 600).withActive(false);
        assertFalse(DoctorScheduleConflicts.overlaps(candidate, Collections.singletonList(entry("other", 1, 540, 600))));
        assertTrue(DoctorScheduleConflicts.overlaps(candidate.withActive(true),
                Collections.singletonList(entry("other", 1, 540, 600))));
    }

    @Test
    public void editingIgnoresOnlyItsOwnDocumentId() {
        DoctorSchedule candidate = entry("same", 1, 540, 600);
        assertFalse(DoctorScheduleConflicts.overlaps(candidate, Collections.singletonList(candidate)));
        assertTrue(DoctorScheduleConflicts.overlaps(candidate, Arrays.asList(candidate, entry("other", 1, 570, 630))));
    }

    @Test
    public void differentSlotDurationsDoNotAvoidWindowOverlap() {
        DoctorSchedule candidate = new DoctorSchedule("new", 1, 540, 660, 30, true);
        assertTrue(DoctorScheduleConflicts.overlaps(candidate,
                Collections.singletonList(new DoctorSchedule("other", 1, 600, 720, 20, true))));
    }

    @Test
    public void disjointWindowsDoNotConflict() {
        assertFalse(DoctorScheduleConflicts.overlaps(entry("new", 1, 540, 600),
                Collections.singletonList(entry("other", 1, 660, 720))));
    }

    @Test
    public void invalidCandidateOrExistingRecordCannotSilentlyPass() {
        assertThrows(IllegalArgumentException.class, () -> DoctorScheduleConflicts.overlaps(null, Collections.emptyList()));
        assertThrows(IllegalArgumentException.class, () -> DoctorScheduleConflicts.overlaps(entry("new", 1, 540, 600),
                Collections.singletonList(entry("invalid", 9, 540, 600))));
    }
}
