package com.example.demo.global.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailHasherTest {

    @Test
    void 같은_이메일은_항상_같은_해시값을_만든다() {
        String first = EmailHasher.hash("user@example.com");
        String second = EmailHasher.hash("user@example.com");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void 다른_이메일은_다른_해시값을_만든다() {
        String first = EmailHasher.hash("user1@example.com");
        String second = EmailHasher.hash("user2@example.com");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void 대소문자만_다른_이메일은_같은_해시값을_만든다() {
        String lower = EmailHasher.hash("user@example.com");
        String upper = EmailHasher.hash("USER@EXAMPLE.COM");

        assertThat(lower).isEqualTo(upper);
    }

    @Test
    void 앞뒤_공백만_다른_이메일은_같은_해시값을_만든다() {
        String trimmed = EmailHasher.hash("user@example.com");
        String padded = EmailHasher.hash("  user@example.com  ");

        assertThat(trimmed).isEqualTo(padded);
    }

    @Test
    void 해시값은_64자리_16진수_문자열이다() {
        String hash = EmailHasher.hash("user@example.com");

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }
}
