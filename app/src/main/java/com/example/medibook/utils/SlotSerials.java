package com.example.medibook.utils;

import java.util.Locale;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import com.example.medibook.models.InventorySlot;

/** Display-only numbers allocated durably in the Firestore inventory namespace, never list positions. */
public final class SlotSerials {
    public static final int MAX = 1_000_000;
    public static final String UNAVAILABLE = "\u2014";
    private SlotSerials() { }
    public static List<InventorySlot> publicationOrder(List<InventorySlot> slots) {
        List<InventorySlot> sorted = new ArrayList<>(slots);
        sorted.sort(Comparator.comparing((InventorySlot s) -> s.startAt)
                .thenComparing(s -> s.endAt).thenComparing(s -> s.id));
        return sorted;
    }
    public static int next(long last) {
        if (last < 0 || last >= MAX) throw new IllegalArgumentException("Invalid serial counter");
        return (int) last + 1;
    }
    public static boolean isAssigned(int number) { return number > 0 && number <= MAX; }
    public static String label(int number, int startMinute, int endMinute) {
        // Schedule times belong in their own fields, never in a slot-number label. The minute
        // arguments are retained for existing callers; an unresolved sidecar is not a time token.
        return isAssigned(number) ? String.format(Locale.ROOT, "#%02d", number) : UNAVAILABLE;
    }
    /** Old RTDB keys remain a wire compatibility detail; numbers come ONLY from Firestore sidecars. */
    public static String legacyDisplay(String token, long midnight, Map<String, Integer> numbers) {
        if (token == null || !token.matches("Q[0-9]{4}")) return UNAVAILABLE;
        int start = Integer.parseInt(token.substring(1)) - 1;
        if (start < 0 || start > 1439) return UNAVAILABLE;
        String prefix = "s" + (midnight + start * 60000L) + "-";
        String result = null;
        for (Map.Entry<String, Integer> entry : numbers.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            try {
                long endMillis = Long.parseLong(entry.getKey().substring(prefix.length()));
                int end = (int) ((endMillis - midnight) / 60000L);
                if (end <= start || end > 1439) continue;
                if (result != null) return UNAVAILABLE;
                result = label(entry.getValue(), start, end);
            } catch (NumberFormatException invalid) { /* Ignore malformed legacy paths. */ }
        }
        return result == null ? UNAVAILABLE : result;
    }
}
