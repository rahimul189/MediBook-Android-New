package com.example.medibook.airquality;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EnvironmentGuidanceTest {
    @Test public void aqiCategoryBoundariesMatchUsEpa() {
        assertEquals(EnvironmentGuidance.Aqi.GOOD, EnvironmentGuidance.aqi(0d));
        assertEquals(EnvironmentGuidance.Aqi.GOOD, EnvironmentGuidance.aqi(50d));
        assertEquals(EnvironmentGuidance.Aqi.MODERATE, EnvironmentGuidance.aqi(51d));
        assertEquals(EnvironmentGuidance.Aqi.MODERATE, EnvironmentGuidance.aqi(100d));
        assertEquals(EnvironmentGuidance.Aqi.SENSITIVE, EnvironmentGuidance.aqi(101d));
        assertEquals(EnvironmentGuidance.Aqi.SENSITIVE, EnvironmentGuidance.aqi(150d));
        assertEquals(EnvironmentGuidance.Aqi.UNHEALTHY, EnvironmentGuidance.aqi(151d));
        assertEquals(EnvironmentGuidance.Aqi.UNHEALTHY, EnvironmentGuidance.aqi(200d));
        assertEquals(EnvironmentGuidance.Aqi.VERY_UNHEALTHY, EnvironmentGuidance.aqi(201d));
        assertEquals(EnvironmentGuidance.Aqi.VERY_UNHEALTHY, EnvironmentGuidance.aqi(300d));
        assertEquals(EnvironmentGuidance.Aqi.HAZARDOUS, EnvironmentGuidance.aqi(301d));
        assertEquals(EnvironmentGuidance.Aqi.HAZARDOUS, EnvironmentGuidance.aqi(500d));
    }

    @Test public void missingOrImpossibleAqiIsUnavailableNeverGuessed() {
        assertEquals(EnvironmentGuidance.Aqi.UNAVAILABLE, EnvironmentGuidance.aqi(null));
        assertEquals(EnvironmentGuidance.Aqi.UNAVAILABLE, EnvironmentGuidance.aqi(-1d));
        assertEquals(EnvironmentGuidance.Aqi.UNAVAILABLE, EnvironmentGuidance.aqi(501d));
        assertEquals(EnvironmentGuidance.Aqi.UNAVAILABLE, EnvironmentGuidance.aqi(Double.NaN));
    }

    @Test public void uvCategoriesMatchTheStandardScale() {
        assertEquals(EnvironmentGuidance.Uv.LOW, EnvironmentGuidance.uv(0d));
        assertEquals(EnvironmentGuidance.Uv.LOW, EnvironmentGuidance.uv(2.9));
        assertEquals(EnvironmentGuidance.Uv.MODERATE, EnvironmentGuidance.uv(3d));
        assertEquals(EnvironmentGuidance.Uv.MODERATE, EnvironmentGuidance.uv(5.9));
        assertEquals(EnvironmentGuidance.Uv.HIGH, EnvironmentGuidance.uv(6d));
        assertEquals(EnvironmentGuidance.Uv.HIGH, EnvironmentGuidance.uv(7.9));
        assertEquals(EnvironmentGuidance.Uv.VERY_HIGH, EnvironmentGuidance.uv(8d));
        assertEquals(EnvironmentGuidance.Uv.VERY_HIGH, EnvironmentGuidance.uv(10.9));
        assertEquals(EnvironmentGuidance.Uv.EXTREME, EnvironmentGuidance.uv(11d));
        assertEquals(EnvironmentGuidance.Uv.UNAVAILABLE, EnvironmentGuidance.uv(null));
        assertEquals(EnvironmentGuidance.Uv.UNAVAILABLE, EnvironmentGuidance.uv(-0.5));
    }

    @Test public void respiratorAdviceOnlyAtParticleRelevantUnhealthyLevels() {
        assertFalse(EnvironmentGuidance.respiratorAdvised(100d, 20d, 40d));
        assertFalse(EnvironmentGuidance.respiratorAdvised(150d, 55.4, 254d));
        assertTrue(EnvironmentGuidance.respiratorAdvised(151d, null, null));
        assertTrue(EnvironmentGuidance.respiratorAdvised(null, 55.5, null));
        assertTrue(EnvironmentGuidance.respiratorAdvised(null, null, 254.1));
        assertFalse(EnvironmentGuidance.respiratorAdvised(null, null, null));
    }

    @Test public void particleCareIsNotOfferedForUnavailableMeasurements() {
        assertFalse(EnvironmentGuidance.particleCareAdvised(null, null));
        assertFalse(EnvironmentGuidance.particleCareAdvised(35.4, 154d));
        assertTrue(EnvironmentGuidance.particleCareAdvised(35.5, null));
        assertTrue(EnvironmentGuidance.particleCareAdvised(null, 155d));
    }
}
