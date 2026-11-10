package com.example.medibook.repositories;

import com.example.medibook.models.Doctor;
import com.example.medibook.utils.DoctorImageUrl;
import com.google.firebase.Timestamp;
import org.junit.Test;
import java.math.BigDecimal;
import java.util.Map;
import static org.junit.Assert.*;

public class DoctorImageTest {
    private Doctor doctor() { return new Doctor("doctor", "Doctor", "Medicine", "MBBS", new BigDecimal("500.00"), "", true); }
    private Map<String, Object> stored() { return DoctorMapper.createMap(doctor(), new Timestamp(100, 0)); }
    @Test public void oldDoctorsWithoutAnImageStillMap() {
        Map<String, Object> old = stored(); old.remove("imageUrl");
        assertEquals("", DoctorMapper.fromMap("doctor", old).getImageUrl());
    }
    @Test public void publicCloudinaryUrlRoundTripsWithoutKeysOrUpload() {
        String url = "https://res.cloudinary.com/demo/image/upload/w_200,c_fill/doctor.jpg";
        Map<String, Object> saved = DoctorMapper.createMap(doctor().withImageUrl(url), new Timestamp(100, 0));
        assertEquals(url, DoctorMapper.fromMap("doctor", saved).getImageUrl());
        assertEquals(url, doctor().withImageUrl(url).withActive(false).getImageUrl());
    }
    @Test public void blankAndHttpsPathsWithQueriesAreValid() {
        assertTrue(DoctorImageUrl.isValid(""));
        assertTrue(DoctorImageUrl.isValid("https://images.example.org/doctor%20one.jpg?width=200&height=200"));
    }
    @Test public void unsafeOrNonHttpsUrlsAreRejectedOnWrite() {
        for (String url : new String[]{"http://example.org/photo.jpg", "file:///avatar", "data:image/png;base64,AAAA",
                "https://user:secret@example.org/photo", "https://example.org:443/photo", "https://example.org/photo#fragment",
                "https://example.org/bad image.jpg", "https://example.org/\nphoto", "https://", "https://example.org/" + "a".repeat(2048)}) {
            assertFalse(url, DoctorImageUrl.isValid(url));
            assertThrows(IllegalArgumentException.class, () -> DoctorMapper.updateMap(doctor().withImageUrl(url), new Object()));
        }
    }
    @Test public void malformedLegacyUrlsFallbackWithoutHidingDoctor() {
        for (Object url : new Object[]{"http://bad.example/photo", "not a URL", 123, null}) {
            Map<String, Object> values = stored(); values.put("imageUrl", url);
            assertEquals("", DoctorMapper.fromMap("doctor", values).getImageUrl());
        }
    }
    @Test public void removingImageWritesBlankButNeverChangesAppointmentSchema() {
        Map<String, Object> changed = DoctorMapper.updateMap(doctor().withImageUrl(""), new Object());
        assertEquals("", changed.get("imageUrl")); assertFalse(changed.containsKey("createdAt"));
        assertEquals(2, DoctorMapper.statusMap(true, new Object()).size());
    }
}
