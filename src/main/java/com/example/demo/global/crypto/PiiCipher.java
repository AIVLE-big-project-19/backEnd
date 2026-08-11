package com.example.demo.global.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * User.email/name 컬럼 암호화 핵심 로직.
 * Hibernate가 Spring 컨텍스트와 무관하게 JPA Converter를 직접 인스턴스화하므로
 * Spring DI에 기대지 않는 정적 유틸리티로 작성한다(HashUtil과 동일한 스타일).
 */
final class PiiCipher {

    private static final String ENC_PREFIX = "ENC:";
    private static final String CIPHER_ALGO = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private PiiCipher() {
    }

    static String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] ivAndCiphertext = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, ivAndCiphertext, 0, iv.length);
            System.arraycopy(ciphertext, 0, ivAndCiphertext, iv.length, ciphertext.length);

            return ENC_PREFIX + Base64.getEncoder().encodeToString(ivAndCiphertext);
        } catch (Exception e) {
            throw new IllegalStateException("개인정보 암호화에 실패했습니다.", e);
        }
    }

    /**
     * 저장된 값이 ENC: 접두사로 시작하지 않거나(마이그레이션 전 레거시 평문) 복호화에
     * 실패하면 예외를 던지지 않고 원문 그대로 반환한다. 지난 배포 장애가 정확히
     * "복호화 실패 시 예외가 요청 전체를 500으로 죽이는" 패턴이었기 때문에, 이 폴백은
     * 선택이 아니라 필수 안전장치다.
     */
    static String decrypt(String stored) {
        if (stored == null || !stored.startsWith(ENC_PREFIX)) {
            return stored;
        }

        try {
            byte[] ivAndCiphertext = Base64.getDecoder().decode(stored.substring(ENC_PREFIX.length()));
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[ivAndCiphertext.length - GCM_IV_LENGTH];
            System.arraycopy(ivAndCiphertext, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(ivAndCiphertext, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return stored;
        }
    }

    private static SecretKeySpec secretKey() {
        return new SecretKeySpec(PiiSecretKeyProvider.deriveKey(), "AES");
    }
}
