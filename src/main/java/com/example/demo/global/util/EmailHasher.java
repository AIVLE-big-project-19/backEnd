package com.example.demo.global.util;

import com.example.demo.global.crypto.PiiSecretKeyProvider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 이메일 조회용 결정적 해시(HMAC-SHA256). email 컬럼 자체는 AES-GCM으로 암호화되어
 * 매번 다른 값이 나오므로 exact-match 조회가 불가능하다 — 별도 emailHash 컬럼으로
 * 조회한다. PiiCipher와 동일하게 Spring DI 없이 동작해야 하므로 완전히 독립된
 * 정적 유틸리티로 둔다. 키 유도는 PiiSecretKeyProvider.deriveKey()로 공유한다.
 */
public final class EmailHasher {

    private static final String HMAC_ALGO = "HmacSHA256";

    private EmailHasher() {
    }

    public static String hash(String email) {
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
