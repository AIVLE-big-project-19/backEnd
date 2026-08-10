package com.example.demo.global.util;

public final class MaskingUtil {

    private MaskingUtil() {
    }

    public static String maskName(String name) {
        if (name == null || name.length() <= 1) {
            return name;
        }
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }

    public static String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        int atIndex = email.indexOf('@');
        if (atIndex < 0) {
            return email;
        }

        String localPart = email.substring(0, atIndex);
        String domain = email.substring(atIndex);

        int visibleLength = localPart.length() >= 3 ? 3 : Math.min(1, localPart.length());
        String visible = localPart.substring(0, visibleLength);
        String masked = "*".repeat(localPart.length() - visibleLength);

        return visible + masked + domain;
    }
}
