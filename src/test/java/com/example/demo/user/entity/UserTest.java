package com.example.demo.user.entity;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    void builder로_LOCAL_사용자를_생성할_수_있다() {
        User user = User.builder()
                .loginId("tester01")
                .email("tester01@example.com")
                .password("encoded-password")
                .name("테스터")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build();

        assertThat(user.getLoginId()).isEqualTo("tester01");
        assertThat(user.getProvider()).isEqualTo(Provider.LOCAL);
        assertThat(user.getRole()).isEqualTo(Role.USER);
    }
}
