package com.example.demo.global.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class HashUtilTest {

    @Test
    void 같은_입력값은_같은_해시를_반환한다() {
        String hash1 = HashUtil.sha256("raw-refresh-token-value");
        String hash2 = HashUtil.sha256("raw-refresh-token-value");

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).isNotEqualTo("raw-refresh-token-value");
    }

    @Test
    void 다른_입력값은_다른_해시를_반환한다() {
        String hash1 = HashUtil.sha256("token-a");
        String hash2 = HashUtil.sha256("token-b");

        assertThat(hash1).isNotEqualTo(hash2);
    }
}
