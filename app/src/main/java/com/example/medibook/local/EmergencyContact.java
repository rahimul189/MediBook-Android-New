package com.example.medibook.local;

/** Only the two selected fields are retained, not a contact ID, URI, photo or address book. */
public final class EmergencyContact {
    public final String name, phone;

    private EmergencyContact(String name, String phone) { this.name = name; this.phone = phone; }

    public static EmergencyContact from(String name, String phone) {
        if (dialable(phone) == null) return null;
        String display = name == null ? "" : name.trim();
        if (display.length() > 200) display = display.substring(0, 200);
        // Provider text must not introduce multiline/control-character UI content.
        display = display.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", " ").trim();
        return new EmergencyContact(display, phone.trim());
    }

    /** A telephone number, not a tel: URI, USSD code, post-dial command or arbitrary Intent. */
    public static String dialable(String raw) {
        if (raw == null || raw.length() > 128) return null;
        String value = raw.trim();
        StringBuilder result = new StringBuilder();
        int digits = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            int digit = Character.digit(c, 10);
            if (digit >= 0) { result.append(digit); digits++; }
            else if (c == '+' && i == 0) result.append(c);
            else if (c != ' ' && c != '-' && c != '(' && c != ')' && c != '.') return null;
        }
        return digits >= 3 && digits <= 20 ? result.toString() : null;
    }
}
