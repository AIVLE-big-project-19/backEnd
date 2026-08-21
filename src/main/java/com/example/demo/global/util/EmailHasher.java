package com.example.demo.global.util;

import com.example.demo.global.crypto.PiiSecretKeyProvider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class EmailHasher {

    private static final String HMAC_ALGO = "HmacSHA256";

    private EmailHasher() {
    }

    public static String hash(String email) {
        if (email == null) {
            return null;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(PiiSecretKeyProvider.deriveKey(), HMAC_ALGO));
            byte[] hashed = mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("이메일 해시 생성에 실패했습니다.", e);
        }
    }
}
