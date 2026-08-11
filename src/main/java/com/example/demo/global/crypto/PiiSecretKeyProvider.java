package com.example.demo.global.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * PiiCipher(AES-256-GCM)와 EmailHasher(HMAC-SHA256)가 공유하는 키 유도 로직.
 * PII_ENCRYPTION_KEY 환경변수(임의 길이 문자열)를 SHA-256으로 해시해 정확히
 * 32바이트 키로 만든다. Hibernate가 JPA Converter를 Spring 컨텍스트와 무관하게
 * 직접 인스턴스화하므로 순수 static 유틸로 둔다(HashUtil과 동일한 스타일) —
 * Spring 빈이 아니다.
 */
public final class PiiSecretKeyProvider {

    private PiiSecretKeyProvider() {
    }

    public static byte[] deriveKey() {
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
