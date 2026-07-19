package com.example.demo.user.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.dto.ConsentStatusResponse;
import com.example.demo.user.entity.ConsentType;
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserConsent;
import com.example.demo.user.repository.UserConsentRepository;
import com.example.demo.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConsentServiceTest {

    @Mock
    private UserConsentRepository userConsentRepository;

    @Mock
    private UserRepository userRepository;

    private ConsentService consentService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        consentService = new ConsentService(userConsentRepository, userRepository, "1.0");
    }

    private User sampleUser() {
        return User.builder()
                .id(1L)
                .loginId("tester01")
                .email("tester01@example.com")
                .name("테스터")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build();
    }

    @Test
    void 가입_동의_기록시_3개_타입_행을_모두_저장한다() {
        User user = sampleUser();

        consentService.recordSignupConsents(user, true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserConsent>> captor = ArgumentCaptor.forClass(List.class);
        verify(userConsentRepository).saveAll(captor.capture());
        List<UserConsent> saved = captor.getValue();

        assertThat(saved).hasSize(3);
        assertThat(saved).extracting(UserConsent::getConsentType)
                .containsExactlyInAnyOrder(ConsentType.TERMS, ConsentType.PRIVACY, ConsentType.MARKETING);
        assertThat(saved).allSatisfy(c -> {
            assertThat(c.getUser()).isEqualTo(user);
            assertThat(c.getTermsVersion()).isEqualTo("1.0");
        });
        assertThat(saved).filteredOn(c -> c.getConsentType() == ConsentType.MARKETING)
                .allSatisfy(c -> assertThat(c.isAgreed()).isTrue());
    }

    @Test
    void 마케팅_미동의로_가입하면_MARKETING_행이_false로_저장된다() {
        User user = sampleUser();

        consentService.recordSignupConsents(user, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserConsent>> captor = ArgumentCaptor.forClass(List.class);
        verify(userConsentRepository).saveAll(captor.capture());

        assertThat(captor.getValue()).filteredOn(c -> c.getConsentType() == ConsentType.MARKETING)
                .allSatisfy(c -> assertThat(c.isAgreed()).isFalse());
        assertThat(captor.getValue()).filteredOn(c -> c.getConsentType() != ConsentType.MARKETING)
                .allSatisfy(c -> assertThat(c.isAgreed()).isTrue());
    }

    @Test
    void 동의_현황_조회시_타입별_최신_행_기준으로_반환한다() {
        User user = sampleUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userConsentRepository.findTopByUserAndConsentTypeOrderByIdDesc(user, ConsentType.TERMS))
                .thenReturn(Optional.of(UserConsent.builder()
                        .user(user).consentType(ConsentType.TERMS).agreed(true).termsVersion("1.0").build()));
        when(userConsentRepository.findTopByUserAndConsentTypeOrderByIdDesc(user, ConsentType.PRIVACY))
                .thenReturn(Optional.of(UserConsent.builder()
                        .user(user).consentType(ConsentType.PRIVACY).agreed(true).termsVersion("1.0").build()));
        when(userConsentRepository.findTopByUserAndConsentTypeOrderByIdDesc(user, ConsentType.MARKETING))
                .thenReturn(Optional.of(UserConsent.builder()
                        .user(user).consentType(ConsentType.MARKETING).agreed(false).termsVersion("1.0").build()));

        ConsentStatusResponse response = consentService.getConsentStatus(1L);

        assertThat(response.getConsents()).hasSize(3);
        assertThat(response.getConsents()).extracting(ConsentStatusResponse.ConsentItem::getType)
                .containsExactly("TERMS", "PRIVACY", "MARKETING");
        assertThat(response.getConsents()).filteredOn(i -> i.getType().equals("MARKETING"))
                .allSatisfy(i -> assertThat(i.getAgreed()).isFalse());
    }

    @Test
    void 동의_기록이_없는_항목은_agreed가_null이다() {
        User user = sampleUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userConsentRepository.findTopByUserAndConsentTypeOrderByIdDesc(eq(user), any(ConsentType.class)))
                .thenReturn(Optional.empty());

        ConsentStatusResponse response = consentService.getConsentStatus(1L);

        assertThat(response.getConsents()).hasSize(3);
        assertThat(response.getConsents()).allSatisfy(i -> {
            assertThat(i.getAgreed()).isNull();
            assertThat(i.getVersion()).isNull();
            assertThat(i.getAgreedAt()).isNull();
        });
    }

    @Test
    void 존재하지_않는_유저로_조회하면_예외가_발생한다() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> consentService.getConsentStatus(99L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    void 마케팅_동의_변경시_새_행을_추가하고_변경된_상태를_반환한다() {
        User user = sampleUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userConsentRepository.save(any(UserConsent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ConsentStatusResponse.ConsentItem item = consentService.updateMarketingConsent(1L, true);

        ArgumentCaptor<UserConsent> captor = ArgumentCaptor.forClass(UserConsent.class);
        verify(userConsentRepository).save(captor.capture());
        UserConsent saved = captor.getValue();

        assertThat(saved.getConsentType()).isEqualTo(ConsentType.MARKETING);
        assertThat(saved.isAgreed()).isTrue();
        assertThat(saved.getTermsVersion()).isEqualTo("1.0");
        assertThat(item.getType()).isEqualTo("MARKETING");
        assertThat(item.getAgreed()).isTrue();
        verify(userConsentRepository, never()).deleteById(any());
    }

    @Test
    void 마케팅_동의_철회도_새_행_추가로_기록된다() {
        User user = sampleUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userConsentRepository.save(any(UserConsent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ConsentStatusResponse.ConsentItem item = consentService.updateMarketingConsent(1L, false);

        assertThat(item.getAgreed()).isFalse();
        verify(userConsentRepository).save(any(UserConsent.class));
    }
}
