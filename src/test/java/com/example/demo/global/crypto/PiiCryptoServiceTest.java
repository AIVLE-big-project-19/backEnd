package com.example.demo.global.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiCryptoServiceTest {

    private final PiiCryptoService piiCryptoService = new PiiCryptoService();

    @Test
    void 암호화한_값을_복호화하면_원문과_같다() {
        String encrypted = piiCryptoService.encrypt("tester01@example.com");

        assertThat(encrypted).isNotEqualTo("tester01@example.com");
        assertThat(piiCryptoService.decrypt(encrypted)).isEqualTo("tester01@example.com");
    }

    @Test
    void 같은_평문을_암호화해도_매번_다른_암호문이_나온다() {
        String first = piiCryptoService.encrypt("tester01@example.com");
        String second = piiCryptoService.encrypt("tester01@example.com");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void 같은_입력값은_같은_검색용_해시를_반환한다() {
        String hash1 = piiCryptoService.hashForSearch("tester01@example.com");
        String hash2 = piiCryptoService.hashForSearch("tester01@example.com");

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).isNotEqualTo("tester01@example.com");
    }

    @Test
    void 다른_입력값은_다른_검색용_해시를_반환한다() {
        String hash1 = piiCryptoService.hashForSearch("a@example.com");
        String hash2 = piiCryptoService.hashForSearch("b@example.com");

        assertThat(hash1).isNotEqualTo(hash2);
    }
}
