package com.example.medibook.wellness;

/**
 * Pure carousel timing and wrap rules, kept free of Android types so the launcher-critical
 * behaviour stays unit testable.
 */
public final class WellnessCarouselPlan {

    /** One card per interval; no other timer is allowed to drive the pager. */
    public static final long INTERVAL_MS = 8_000L;

    private WellnessCarouselPlan() { }

    /** Next page for the automatic advance, wrapping from the last card back to the first. */
    public static int nextPage(int current, int count) {
        if (count <= 0) return 0;
        if (current < 0 || current >= count) return 0;
        return (current + 1) % count;
    }
}
