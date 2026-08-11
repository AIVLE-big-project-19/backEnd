package com.example.demo.global.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiCipherTest {

    @Test
    void 암호화한_값을_복호화하면_원문과_같다() {
        String plaintext = "user@example.com";

        String encrypted = PiiCipher.encrypt(plaintext);
        String decrypted = PiiCipher.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void 같은_평문도_암호화할때마다_다른_값이_나온다() {
        String plaintext = "user@example.com";

        String first = PiiCipher.encrypt(plaintext);
        String second = PiiCipher.encrypt(plaintext);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void 암호문은_ENC_접두사로_시작한다() {
        String encrypted = PiiCipher.encrypt("user@example.com");

        assertThat(encrypted).startsWith("ENC:");
    }

    @Test
    void ENC_접두사가_없는_레거시_평문은_그대로_반환한다() {
        String legacyPlaintext = "legacy-user@example.com";

        String result = PiiCipher.decrypt(legacyPlaintext);

        assertThat(result).isEqualTo(legacyPlaintext);
    }

    @Test
    void 손상된_암호문은_복호화_실패시_원문_그대로_반환한다() {
        String corrupted = "ENC:not-a-valid-base64-ciphertext!!";

        String result = PiiCipher.decrypt(corrupted);

        assertThat(result).isEqualTo(corrupted);
    }

    @Test
    void null을_암호화하면_null을_반환한다() {
        assertThat(PiiCipher.encrypt(null)).isNull();
    }

    @Test
    void null을_복호화하면_null을_반환한다() {
        assertThat(PiiCipher.decrypt(null)).isNull();
    }
}
