package com.example.medibook.utils;

import com.example.medibook.models.DoctorSchedule;

import org.junit.Test;

import static com.example.medibook.utils.DoctorScheduleValidation.Error.*;
import static org.junit.Assert.*;

/** Synthetic, offline inputs only; none are application schedule records. */
public class DoctorScheduleValidationTest {
    @Test
    public void allRequiredSelectionsMustBeProvided() {
        DoctorScheduleValidation.Result result = DoctorScheduleValidation.validate(-1, -1, -1, null);
        assertEquals(DAY, result.dayError);
        assertEquals(START, result.startError);
        assertEquals(END, result.endError);
        assertEquals(SLOT_REQUIRED, result.slotError);
        assertFalse(result.isValid());
    }

    @Test
    public void daysUseMondayOneThroughSundaySeven() {
        for (int day = 1; day <= 7; day++) assertTrue(DoctorScheduleValidation.validate(day, 540, 600, "15").isValid());
        for (int day : new int[]{-1, 0, 8, Integer.MAX_VALUE}) {
            assertEquals(DAY, DoctorScheduleValidation.validate(day, 540, 600, "15").dayError);
        }
    }

    @Test
    public void validEntryRetainsPickedValuesAndDocumentIdentity() {
        DoctorSchedule entry = DoctorScheduleValidation.validate(3, 570, 645, " 15 ").toSchedule("schedule-1", true);
        assertEquals("schedule-1", entry.getId());
        assertEquals(3, entry.getDayOfWeek());
        assertEquals(570, entry.getStartMinute());
        assertEquals(645, entry.getEndMinute());
        assertEquals(15, entry.getSlotMinutes());
        assertTrue(entry.isActive());
    }

    @Test
    public void midnightIsAValidStartRatherThanAMissingSelection() {
        assertTrue(DoctorScheduleValidation.validate(1, 0, 30, "15").isValid());
    }

    @Test
    public void lastMinuteOfTheDayIsAnAllowedEnd() {
        assertTrue(DoctorScheduleValidation.validate(7, 1410, 1439, "5").isValid());
    }

    @Test
    public void timesOutsideTheDayAreRejected() {
        for (int minute : new int[]{-1, 1440, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            assertEquals(START, DoctorScheduleValidation.validate(1, minute, 600, "15").startError);
            assertEquals(END, DoctorScheduleValidation.validate(1, 540, minute, "15").endError);
        }
    }

    @Test
    public void equalStartAndEndAreNotAnAllDayEntry() {
        assertEquals(ORDER, DoctorScheduleValidation.validate(1, 540, 540, "15").endError);
    }

    @Test
    public void reversedAndOvernightWindowsAreRejected() {
        assertEquals(ORDER, DoctorScheduleValidation.validate(1, 600, 540, "15").endError);
        assertEquals(ORDER, DoctorScheduleValidation.validate(1, 1380, 60, "15").endError);
        assertEquals(ORDER, DoctorScheduleValidation.validate(1, 1380, 0, "15").endError);
    }

    @Test
    public void emptySlotIsRequiredRatherThanAZeroDuration() {
        for (String value : new String[]{null, "", "   "}) {
            assertEquals(SLOT_REQUIRED, DoctorScheduleValidation.validate(1, 540, 600, value).slotError);
        }
    }

    @Test
    public void zeroNegativeAndOverLimitDurationsAreRejected() {
        for (String value : new String[]{"0", "-5", "4", "181", "185", "999", "9".repeat(1000)}) {
            assertEquals(value, SLOT_INVALID, DoctorScheduleValidation.validate(1, 0, 1439, value).slotError);
        }
    }

    @Test
    public void slotsMustBeWholeFiveMinuteIncrements() {
        for (String value : new String[]{"7", "16", "15.0", "1e1", "+15", "15 minutes", "NaN", "Infinity"}) {
            assertEquals(value, SLOT_INVALID, DoctorScheduleValidation.validate(1, 0, 1439, value).slotError);
        }
    }

    @Test
    public void smallestAndLargestDurationsAreAcceptedWhenTheyFit() {
        assertTrue(DoctorScheduleValidation.validate(1, 540, 545, "5").isValid());
        assertTrue(DoctorScheduleValidation.validate(1, 540, 720, "180").isValid());
    }

    @Test
    public void slotCannotExceedItsTimeWindow() {
        assertEquals(SLOT_WINDOW, DoctorScheduleValidation.validate(1, 540, 550, "15").slotError);
    }

    @Test
    public void windowTooShortForAnySlotIsRejected() {
        assertEquals(SLOT_WINDOW, DoctorScheduleValidation.validate(1, 540, 544, "5").slotError);
    }

    @Test
    public void aRemainderDoesNotGenerateOrRequireAPartialBookingSlot() {
        // This is a weekly window, not appointment generation or capacity validation.
        assertTrue(DoctorScheduleValidation.validate(1, 540, 605, "30").isValid());
    }

    @Test
    public void validationRejectsMalformedIdsAndFields() {
        assertFalse(DoctorScheduleValidation.isValid(null));
        assertFalse(DoctorScheduleValidation.isValid(new DoctorSchedule("bad/id", 1, 540, 600, 15, true)));
        assertFalse(DoctorScheduleValidation.isValid(new DoctorSchedule("schedule-1", 9, 540, 600, 15, true)));
        for (String id : new String[]{null, "", "x".repeat(129), "bad/id", ".", "..", "space id", "বাংলা"}) {
            assertFalse(DoctorScheduleValidation.validId(id));
        }
    }

    @Test
    public void persistedIdsAllowAutoIdsAndThe128CharacterBoundary() {
        for (String id : new String[]{"abc_123-XYZ", "A", "x".repeat(65), "x".repeat(128)}) {
            assertTrue(DoctorScheduleValidation.validId(id));
        }
    }

    @Test(expected = IllegalStateException.class)
    public void invalidFormCannotProduceADraft() {
        DoctorScheduleValidation.validate(1, 600, 540, "15").toSchedule("schedule-1", false);
    }

    @Test(expected = IllegalStateException.class)
    public void validFormCannotProduceADraftWithAnInvalidIdentity() {
        DoctorScheduleValidation.validate(1, 540, 600, "15").toSchedule("bad/id", false);
    }
}
