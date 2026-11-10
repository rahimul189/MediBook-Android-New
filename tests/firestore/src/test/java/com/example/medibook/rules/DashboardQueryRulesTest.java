package com.example.medibook.rules;

import org.junit.Test;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Instant;
import static com.example.medibook.rules.RulesClient.*;
import static org.junit.Assert.*;

/** Exact bounded Android source-query shapes; RTDB cannot substitute for these ownership checks. */
public class DashboardQueryRulesTest extends RulesFixtures {
    @Test public void patientUpcomingQuerySelectsOnlyOwnedFutureConfirmedAppointments() {
        LocalDate day = nextDhakaDate(DayOfWeek.MONDAY);
        seed("appointments/earlier", appointmentRecord(day, 540, 560, "Confirmed"));
        seed("appointments/later", appointmentRecord(day, 600, 620, "Confirmed"));
        seed("appointments/cancelled", appointmentRecord(day, 520, 540, "Cancelled"));
        seed("appointments/foreign", merge(appointmentRecord(day, 500, 520, "Confirmed"), map("patientUid", "patient-b")));
        java.util.List<com.google.gson.JsonObject> values = documents(patient().upcomingAppointments("patient-a", new Time(Instant.now().toString())));
        assertEquals(2, values.size());
        assertTrue(values.get(0).get("name").getAsString().endsWith("/earlier"));
        assertTrue(values.get(1).get("name").getAsString().endsWith("/later"));
    }
    @Test public void crossPatientAndAnonymousUpcomingQueriesAreDenied() {
        Time now = new Time(Instant.now().toString());
        denied(patient().upcomingAppointments("patient-b", now));
        denied(anonymous().upcomingAppointments("patient-a", now));
        denied(authenticated("missing-profile", map()).upcomingAppointments("missing-profile", now));
    }
    @Test public void onlyTrustedAdminCanReadTheGlobalScheduledDateRange() {
        LocalDate saturday = LocalDate.now(HOSPITAL_ZONE).with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY));
        allowed(admin().scheduledWeek(dhakaMidnight(saturday), dhakaMidnight(saturday.plusDays(7))));
        denied(patient().scheduledWeek(dhakaMidnight(saturday), dhakaMidnight(saturday.plusDays(7))));
        denied(anonymous().scheduledWeek(dhakaMidnight(saturday), dhakaMidnight(saturday.plusDays(7))));
    }
}
