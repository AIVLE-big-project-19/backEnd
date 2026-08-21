package com.example.demo.global.crypto;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Slf4j
public final class PiiSecretKeyProvider {

    private PiiSecretKeyProvider() {
    }

    public static byte[] deriveKey() {
        String secret = System.getProperty("PII_ENCRYPTION_KEY", System.getenv("PII_ENCRYPTION_KEY"));
        if (secret == null || secret.isBlank()) {
            secret = "dev-only-pii-key-please-override-in-real-env";
            log.warn("PII_ENCRYPTION_KEY 환경변수가 설정되지 않아 개발용 기본 키를 사용합니다. 운영 환경에서는 반드시 설정하세요.");
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
