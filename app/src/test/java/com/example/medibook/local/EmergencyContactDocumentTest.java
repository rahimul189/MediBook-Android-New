package com.example.medibook.local;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class EmergencyContactDocumentTest {
    private static Map<String, Object> doc(List<EmergencyContact> values) {
        Map<String, Object> raw = new HashMap<>(); raw.put("contacts", EmergencyContactDocument.slots(values));
        raw.put("phoneKeys", EmergencyContactDocument.phoneKeys(values)); return raw;
    }
    @Test public void roundTripNormalizesNumbersAndBuildsBijectiveIndex() {
        List<EmergencyContact> input = Arrays.asList(EmergencyContact.from("Home", "+880 (1712) 345-678"), EmergencyContact.from("Help", "999"));
        Map<String, Object> raw = doc(input);
        List<EmergencyContact> decoded = EmergencyContactDocument.read(raw);
        assertEquals(2, decoded.size()); assertEquals("+8801712345678", decoded.get(0).phone);
        assertEquals("c0", EmergencyContactDocument.phoneKeys(input).get("8801712345678"));
        assertEquals("c1", EmergencyContactDocument.phoneKeys(input).get("999"));
        assertEquals(3, ((Map<?, ?>) EmergencyContactDocument.slots(input).get("c0")).size());
    }
    @Test public void normalizationDeduplicatesAndLimitsToTen() {
        List<EmergencyContact> input = new ArrayList<>();
        input.add(EmergencyContact.from("A", "+123-456")); input.add(EmergencyContact.from("Same", "123 456"));
        for (int i = 0; i < 15; i++) input.add(EmergencyContact.from("Contact " + i, "999" + i));
        assertEquals(10, EmergencyContactDocument.normalize(input).size());
        assertEquals(10, EmergencyContactDocument.phoneKeys(input).size());
        assertEquals(10, EmergencyContactDocument.read(doc(input)).size());
    }
    @Test public void missingMalformedAndForgedSlotsAreIgnored() {
        assertTrue(EmergencyContactDocument.read(null).isEmpty());
        assertTrue(EmergencyContactDocument.read("bad").isEmpty());
        assertTrue(EmergencyContactDocument.read(Collections.singletonMap("contacts", "bad")).isEmpty());
        Map<String, Object> raw = doc(Collections.singletonList(EmergencyContact.from("Home", "999")));
        Map<?, ?> slots = (Map<?, ?>) raw.get("contacts");
        @SuppressWarnings("unchecked") Map<String, Object> first = (Map<String, Object>) slots.get("c0");
        first.put("phoneKey", "111"); assertTrue(EmergencyContactDocument.read(raw).isEmpty());
        first.put("phoneKey", 999); assertTrue(EmergencyContactDocument.read(raw).isEmpty());
    }
    @Test public void unicodeProviderDigitsAndControlTextAreSanitizedForFirestore() {
        EmergencyContact value = EmergencyContact.from("A\nB\u0085C\u2028D", "৯৯৯");
        List<EmergencyContact> clean = EmergencyContactDocument.normalize(Collections.singletonList(value));
        assertEquals("999", clean.get(0).phone); assertEquals("A B C D", clean.get(0).name);
    }
}
