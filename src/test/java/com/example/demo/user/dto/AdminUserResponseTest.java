package com.example.demo.user.dto;

import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminUserResponseTest {

    @Test
    void from은_이름과_이메일을_마스킹해서_담는다() {
        User user = User.builder()
                .id(1L)
                .loginId("hansy")
                .email("s2ungyeon.h@gmail.com")
                .name("한승연")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build();

        AdminUserResponse response = AdminUserResponse.from(user);

        assertThat(response.getName()).isEqualTo("한**");
        assertThat(response.getEmail()).isEqualTo("s2u********@gmail.com");
        assertThat(response.getLoginId()).isEqualTo("hansy");
    }
}
