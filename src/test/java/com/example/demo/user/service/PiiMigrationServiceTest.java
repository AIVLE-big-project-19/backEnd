package com.example.demo.user.service;

import com.example.demo.global.util.EmailHasher;
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PiiMigrationServiceTest {

    @Mock
    private UserRepository userRepository;

    private PiiMigrationService piiMigrationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        piiMigrationService = new PiiMigrationService(userRepository);
    }

    @Test
    void emailHash가_없는_기존_행에_해시를_채우고_저장한다() {
        User legacyUser = User.builder()
                .id(1L)
                .email("legacy@example.com")
                .name("레거시유저")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build();
        when(userRepository.findByEmailHashIsNull()).thenReturn(List.of(legacyUser));

        piiMigrationService.migratePendingUsers();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<User>> captor = ArgumentCaptor.forClass(List.class);
        verify(userRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getEmailHash()).isEqualTo(EmailHasher.hash("legacy@example.com"));
    }

    @Test
    void 마이그레이션_대상이_없으면_저장을_호출하지_않는다() {
        when(userRepository.findByEmailHashIsNull()).thenReturn(List.of());

        piiMigrationService.migratePendingUsers();

        verify(userRepository, never()).saveAll(any());
    }
}
