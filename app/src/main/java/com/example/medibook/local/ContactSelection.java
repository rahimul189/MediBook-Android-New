package com.example.medibook.local;

import java.net.URI;

/** JVM-testable result boundary. The Android reader supplies the actual ContentResolver query. */
public final class ContactSelection {
    public enum Status {
        SELECTED, CANCELLED, INVALID_RESULT, NO_NUMBER, UNAVAILABLE,
        /** The selected number is already stored, or the 10-contact limit has been reached. */
        DUPLICATE, LIMIT, SYNC_PENDING
    }
    public interface Reader { EmergencyContact read() throws Exception; }
    public final Status status;
    public final EmergencyContact contact;
    private ContactSelection(Status status, EmergencyContact contact) { this.status = status; this.contact = contact; }

    public static ContactSelection read(boolean success, String uri, Reader reader) {
        if (!success) return new ContactSelection(Status.CANCELLED, null);
        if (!validUri(uri)) return new ContactSelection(Status.INVALID_RESULT, null);
        try {
            EmergencyContact contact = reader.read();
            return new ContactSelection(contact == null ? Status.NO_NUMBER : Status.SELECTED, contact);
        } catch (Exception revokedOrUnavailable) {
            return new ContactSelection(Status.UNAVAILABLE, null);
        }
    }

    public static boolean validUri(String value) {
        if (value == null || value.length() > 4096) return false;
        try {
            URI uri = new URI(value);
            String authority = uri.getRawAuthority();
            // Android may prefix a Contacts authority with a numeric work-profile user ID.
            return "content".equals(uri.getScheme()) && authority != null
                    && authority.matches("(?:[0-9]+@)?com\\.android\\.contacts")
                    && uri.getPath() != null && (uri.getPath().startsWith("/data/")
                    || uri.getPath().startsWith("/contacts/")) && uri.getFragment() == null;
        } catch (Exception malformed) { return false; }
    }
}
