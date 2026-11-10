package com.example.medibook.adapters;

import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListUpdateCallback;
import com.example.medibook.models.Appointment;
import com.example.medibook.models.AppointmentStatus;
import com.google.firebase.Timestamp;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppointmentRowDiffTest {
    private static Appointment row(String id, AppointmentStatus status) {
        return new Appointment(id, "self", "Patient", "01712345678", "doctor", "Doctor", "Medicine", 100,
                "schedule", 1, new Timestamp(100, 0), 540, 560, new Timestamp(1000, 0), new Timestamp(2000, 0),
                status, new Timestamp(1, 0), new Timestamp(2, 0));
    }
    private static List<Appointment> rows() { return Arrays.asList(row("a", AppointmentStatus.CONFIRMED), row("b", AppointmentStatus.CONFIRMED)); }
    private static final class Recorder implements ListUpdateCallback {
        int inserted, removed, changed, moved;
        @Override public void onInserted(int p, int c) { inserted += c; }
        @Override public void onRemoved(int p, int c) { removed += c; }
        @Override public void onMoved(int a, int b) { moved++; }
        @Override public void onChanged(int p, int c, Object payload) { changed += c; }
    }
    private Recorder diff(List<Appointment> before, List<Appointment> after, boolean oldAdmin, boolean admin, long oldTime, long now) {
        Recorder result = new Recorder();
        DiffUtil.calculateDiff(new AppointmentRowDiff(before, after, oldAdmin, admin, oldTime, now)).dispatchUpdatesTo(result);
        return result;
    }
    @Test public void unchangedRefreshAndClockTicksProduceZeroRowUpdates() {
        Recorder result = diff(rows(), rows(), false, false, 1_000_000, 1_030_000);
        assertEquals(0, result.changed + result.inserted + result.removed + result.moved);
    }
    @Test public void serialLookupRebindsOnlyItsOneChangedRow() {
        List<Appointment> before = rows(), after = Arrays.asList(before.get(0).withSlotSerial(3), before.get(1));
        Recorder result = diff(before, after, false, false, 1_000_000, 1_030_000);
        assertEquals(1, result.changed); assertEquals(0, result.inserted + result.removed + result.moved);
    }
    @Test public void timeDerivedCompletionStillUpdatesAtTheEndBoundary() {
        Recorder result = diff(rows(), rows(), false, false, 1_999_999, 2_000_000);
        assertEquals(2, result.changed);
    }
    @Test public void cancellationChangesOnlyTheAffectedRow() {
        Recorder result = diff(rows(), Arrays.asList(row("a", AppointmentStatus.CANCELLED), row("b", AppointmentStatus.CONFIRMED)), false, false, 1000, 2000);
        assertEquals(1, result.changed); assertEquals(0, result.removed + result.inserted);
    }
    @Test public void filteringUsesRemovalAndInsertionNotReplacementOrFullRefresh() {
        List<Appointment> before = rows();
        Recorder narrow = diff(before, java.util.Collections.singletonList(before.get(1)), false, false, 1000, 2000);
        assertEquals(1, narrow.removed); assertEquals(0, narrow.changed + narrow.inserted);
        Recorder restore = diff(java.util.Collections.singletonList(before.get(1)), before, false, false, 1000, 2000);
        assertEquals(1, restore.inserted); assertEquals(0, restore.changed + restore.removed);
    }
    @Test public void adminTitleModeChangeRebindsVisibleRows() {
        Recorder result = diff(rows(), rows(), false, true, 1000, 2000); assertEquals(2, result.changed);
    }
}
