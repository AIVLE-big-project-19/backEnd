package com.example.demo.global.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiSecretKeyProviderTest {

    @Test
    void 키는_32바이트다() {
        assertThat(PiiSecretKeyProvider.deriveKey()).hasSize(32);
    }

    @Test
    void 매번_같은_키를_반환한다() {
        byte[] first = PiiSecretKeyProvider.deriveKey();
        byte[] second = PiiSecretKeyProvider.deriveKey();

        assertThat(first).isEqualTo(second);
    }
}
