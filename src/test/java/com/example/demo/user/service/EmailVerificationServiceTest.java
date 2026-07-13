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
        when(redisTemplate.hasKey("email-code:test@example.com")).thenReturn(false);

        emailVerificationService.sendCode("test@example.com");

        verify(valueOperations).set(eq("email-code:test@example.com"), anyString(), eq(5L), eq(TimeUnit.MINUTES));
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void 이미_코드가_발송된_상태면_쿨다운_예외가_발생한다() {
        when(redisTemplate.hasKey("email-code:test@example.com")).thenReturn(true);

        assertThatThrownBy(() -> emailVerificationService.sendCode("test@example.com"))
                .isInstanceOf(CustomException.class);
    }

    @Test
    void 코드가_일치하면_인증완료_플래그를_세팅한다() {
        when(valueOperations.get("email-code:test@example.com")).thenReturn("123456");

        emailVerificationService.verifyCode("test@example.com", "123456");

        verify(valueOperations).set(eq("email-verified:test@example.com"), eq("true"), eq(30L), eq(TimeUnit.MINUTES));
        verify(redisTemplate).delete("email-code:test@example.com");
    }

    @Test
    void 코드가_불일치하면_예외가_발생한다() {
        when(valueOperations.get("email-code:test@example.com")).thenReturn("123456");

        assertThatThrownBy(() -> emailVerificationService.verifyCode("test@example.com", "000000"))
                .isInstanceOf(CustomException.class);
    }

    @Test
    void 인증완료_플래그가_있으면_isVerified는_true다() {
        when(redisTemplate.hasKey("email-verified:test@example.com")).thenReturn(true);

        assertThat(emailVerificationService.isVerified("test@example.com")).isTrue();
    }
}
