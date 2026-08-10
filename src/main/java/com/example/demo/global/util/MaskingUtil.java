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
}
