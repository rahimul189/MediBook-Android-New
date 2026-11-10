package com.example.medibook.wellness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.example.medibook.R;

import org.junit.Test;

/** Guards the exact eleven-card set and the 8-second wrap behaviour without Android views. */
public class WellnessCarouselTest {

    @Test public void autoAdvanceRunsEveryEightSeconds() {
        assertEquals(8000L, WellnessCarouselPlan.INTERVAL_MS);
    }

    @Test public void advancesExactlyOneCardPerInterval() {
        assertEquals(1, WellnessCarouselPlan.nextPage(0, 11));
        assertEquals(6, WellnessCarouselPlan.nextPage(5, 11));
    }

    @Test public void wrapsFromTheLastCardBackToTheFirst() {
        assertEquals(0, WellnessCarouselPlan.nextPage(10, 11));
    }

    @Test public void emptyOrOutOfRangeCarouselStaysOnTheFirstCard() {
        assertEquals(0, WellnessCarouselPlan.nextPage(0, 0));
        assertEquals(0, WellnessCarouselPlan.nextPage(4, 0));
        assertEquals(0, WellnessCarouselPlan.nextPage(11, 11));
    }

    @Test public void allElevenOriginalCardsArePresentInArchiveOrder() {
        int[] cards = WellnessCards.drawables();
        assertEquals(11, WellnessCards.count());
        assertEquals(R.drawable.wellness_stay_hydrated, cards[0]);
        assertEquals(R.drawable.wellness_balanced_diet, cards[1]);
        assertEquals(R.drawable.wellness_physical_activity, cards[2]);
        assertEquals(R.drawable.wellness_quality_sleep, cards[3]);
        assertEquals(R.drawable.wellness_hand_hygiene, cards[4]);
        assertEquals(R.drawable.wellness_meet_our_doctors, cards[5]);
        assertEquals(R.drawable.wellness_book_consultation, cards[6]);
        assertEquals(R.drawable.wellness_find_a_doctor, cards[7]);
        assertEquals(R.drawable.wellness_book_appointment, cards[8]);
        assertEquals(R.drawable.wellness_track_appointments, cards[9]);
        assertEquals(R.drawable.wellness_appointment_reminders, cards[10]);
        for (int first = 0; first < cards.length; first++) {
            for (int second = first + 1; second < cards.length; second++) {
                assertNotEquals("card " + first + " duplicates card " + second, cards[first], cards[second]);
            }
        }
    }
}
