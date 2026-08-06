package com.example.demo.global.crypto;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * email/name 등 개인정보 컬럼의 AES-256-GCM 암/복호화와 검색용 HMAC-SHA256 해시.
 * JPA AttributeConverter는 Hibernate가 (Spring 컨텍스트 범위와 무관하게) 직접
 * 인스턴스화하므로, 여기서는 Spring 빈 주입 없이 시스템 프로퍼티/환경변수를 직접 읽는다.
 */
final class PiiCipher {

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String DEV_DEFAULT_KEY =
            "this-is-a-dev-only-pii-key-please-override-in-real-env-32bytes-min";

    private static final SecretKeySpec AES_KEY;
    private static final SecretKeySpec HMAC_KEY;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    static {
        byte[] keyBytes = sha256(resolveSecret().getBytes(StandardCharsets.UTF_8));
        AES_KEY = new SecretKeySpec(keyBytes, "AES");
        HMAC_KEY = new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    private PiiCipher() {
    }

    private static String resolveSecret() {
        String fromProperty = System.getProperty("PII_ENCRYPTION_KEY");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty;
        }
        String fromEnv = System.getenv("PII_ENCRYPTION_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return DEV_DEFAULT_KEY;
    }

    static String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, AES_KEY, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("개인정보 암호화에 실패했습니다.", e);
        }
    }

    static String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, AES_KEY, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("개인정보 복호화에 실패했습니다.", e);
        }
    }

    static String hashForSearch(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(HMAC_KEY);
            byte[] hashed = mac.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (Exception e) {
            throw new IllegalStateException("검색용 해시 생성에 실패했습니다.", e);
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
