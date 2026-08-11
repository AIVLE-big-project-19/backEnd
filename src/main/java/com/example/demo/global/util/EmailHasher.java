package com.example.demo.global.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 이메일 조회용 결정적 해시(HMAC-SHA256). email 컬럼 자체는 AES-GCM으로 암호화되어
 * 매번 다른 값이 나오므로 exact-match 조회가 불가능하다 — 별도 emailHash 컬럼으로
 * 조회한다. PiiCipher와 동일하게 Spring DI 없이 동작해야 하므로 완전히 독립된
 * 정적 유틸리티로 둔다(약간의 키 로딩 코드 중복은 이 결합을 피하기 위한 의도적 선택).
 */
public final class EmailHasher {

    private static final String HMAC_ALGO = "HmacSHA256";

    private EmailHasher() {
    }

    public static String hash(String email) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secretKeyBytes(), HMAC_ALGO));
            byte[] hashed = mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("이메일 해시 생성에 실패했습니다.", e);
        }
    }

    private static byte[] secretKeyBytes() {
        String secret = System.getProperty("PII_ENCRYPTION_KEY", System.getenv("PII_ENCRYPTION_KEY"));
        if (secret == null || secret.isBlank()) {
            secret = "dev-only-pii-key-please-override-in-real-env";
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
