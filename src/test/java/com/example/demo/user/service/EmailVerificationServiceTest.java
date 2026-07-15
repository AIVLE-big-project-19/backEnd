package com.example.demo.user.service;

import com.example.demo.global.exception.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EmailVerificationServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private MailSender mailSender;

    private EmailVerificationService emailVerificationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        emailVerificationService = new EmailVerificationService(redisTemplate, mailSender);
    }

    @Test
    void 코드를_발송하면_Redis에_저장하고_메일을_보낸다() {
        when(redisTemplate.hasKey("email-code-cooldown:test@example.com")).thenReturn(false);

        emailVerificationService.sendCode("test@example.com");

        verify(valueOperations).set(eq("email-code:test@example.com"), anyString(), eq(5L), eq(TimeUnit.MINUTES));
        verify(valueOperations).set(eq("email-code-cooldown:test@example.com"), anyString(), eq(1L), eq(TimeUnit.MINUTES));
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void 이미_코드가_발송된_상태면_쿨다운_예외가_발생한다() {
        when(redisTemplate.hasKey("email-code-cooldown:test@example.com")).thenReturn(true);

        assertThatThrownBy(() -> emailVerificationService.sendCode("test@example.com"))
                .isInstanceOf(CustomException.class);
    }

    @Test
    void 코드가_일치하면_인증완료_플래그를_세팅한다() {
        when(valueOperations.get("email-code:test@example.com")).thenReturn("123456");

        emailVerificationService.verifyCode("test@example.com", "123456");

        verify(valueOperations).set(eq("email-verified:test@example.com"), eq("true"), eq(30L), eq(TimeUnit.MINUTES));
        verify(redisTemplate).delete("email-code:test@example.com");
        verify(redisTemplate).delete("email-code-attempts:test@example.com");
    }

    @Test
    void 코드가_불일치하면_예외가_발생한다() {
        when(valueOperations.get("email-code:test@example.com")).thenReturn("123456");
        when(valueOperations.increment("email-code-attempts:test@example.com")).thenReturn(1L);

        assertThatThrownBy(() -> emailVerificationService.verifyCode("test@example.com", "000000"))
                .isInstanceOf(CustomException.class);
    }

    @Test
    void 한번_틀려도_잠기지_않고_다음에_정답이면_통과한다() {
        when(valueOperations.get("email-code:test@example.com")).thenReturn("123456");
        when(valueOperations.increment("email-code-attempts:test@example.com")).thenReturn(1L);

        assertThatThrownBy(() -> emailVerificationService.verifyCode("test@example.com", "000000"))
                .isInstanceOf(CustomException.class);

        // 1회 실패는 잠금 임계치(5회) 미만이므로 코드 키가 삭제되지 않아야 한다
        verify(redisTemplate, never()).delete("email-code:test@example.com");

        // 이어서 정답을 제출하면 통과해야 한다
        emailVerificationService.verifyCode("test@example.com", "123456");

        verify(valueOperations).set(eq("email-verified:test@example.com"), eq("true"), eq(30L), eq(TimeUnit.MINUTES));
    }

    @Test
    void 오답을_5회_제출하면_코드가_무효화되고_이후_정답도_실패한다() {
        when(valueOperations.get("email-code:test@example.com")).thenReturn("123456");
        when(valueOperations.increment("email-code-attempts:test@example.com"))
                .thenReturn(1L, 2L, 3L, 4L, 5L);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> emailVerificationService.verifyCode("test@example.com", "000000"))
                    .isInstanceOf(CustomException.class);
        }

        verify(redisTemplate).delete("email-code:test@example.com");
        verify(redisTemplate).delete("email-code-attempts:test@example.com");

        // 코드가 삭제된 이후에는 원래 정답이었던 코드로도 검증에 실패한다 (get이 더 이상 값을 반환하지 않는 상황을 재현)
        when(valueOperations.get("email-code:test@example.com")).thenReturn(null);
        when(valueOperations.increment("email-code-attempts:test@example.com")).thenReturn(1L);

        assertThatThrownBy(() -> emailVerificationService.verifyCode("test@example.com", "123456"))
                .isInstanceOf(CustomException.class);
    }

    @Test
    void 인증완료_플래그가_있으면_isVerified는_true다() {
        when(redisTemplate.hasKey("email-verified:test@example.com")).thenReturn(true);

        assertThat(emailVerificationService.isVerified("test@example.com")).isTrue();
    }

    @Test
    void 메일_발송_실패하면_Redis에_저장되지_않고_예외가_전파된다() {
        when(redisTemplate.hasKey("email-code-cooldown:test@example.com")).thenReturn(false);
        doThrow(new RuntimeException("SMTP down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> emailVerificationService.sendCode("test@example.com"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("SMTP down");

        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void verifyCodeOnly_성공하면_email_verified_플래그는_세팅하지_않는다() {
        when(valueOperations.get("email-code:test@example.com")).thenReturn("123456");

        emailVerificationService.verifyCodeOnly("test@example.com", "123456");

        verify(valueOperations, never()).set(eq("email-verified:test@example.com"), anyString(), anyLong(), any(TimeUnit.class));
        verify(redisTemplate).delete("email-code:test@example.com");
        verify(redisTemplate).delete("email-code-attempts:test@example.com");
    }

    @Test
    void verifyCodeOnly_불일치하면_예외가_발생한다() {
        when(valueOperations.get("email-code:test@example.com")).thenReturn("123456");
        when(valueOperations.increment("email-code-attempts:test@example.com")).thenReturn(1L);

        assertThatThrownBy(() -> emailVerificationService.verifyCodeOnly("test@example.com", "000000"))
                .isInstanceOf(CustomException.class);
    }

    @Test
    void setIdentityVerified_호출하면_10분_TTL로_저장한다() {
        emailVerificationService.setIdentityVerified("tester01");

        verify(valueOperations).set(eq("identity-verified:tester01"), eq("true"), eq(10L), eq(TimeUnit.MINUTES));
    }

    @Test
    void isIdentityVerified_플래그가_있으면_true를_반환한다() {
        when(redisTemplate.hasKey("identity-verified:tester01")).thenReturn(true);

        assertThat(emailVerificationService.isIdentityVerified("tester01")).isTrue();
    }

    @Test
    void isIdentityVerified_플래그가_없으면_false를_반환한다() {
        when(redisTemplate.hasKey("identity-verified:tester01")).thenReturn(false);

        assertThat(emailVerificationService.isIdentityVerified("tester01")).isFalse();
    }

    @Test
    void clearIdentityVerified_호출하면_키를_삭제한다() {
        emailVerificationService.clearIdentityVerified("tester01");

        verify(redisTemplate).delete("identity-verified:tester01");
    }
}
