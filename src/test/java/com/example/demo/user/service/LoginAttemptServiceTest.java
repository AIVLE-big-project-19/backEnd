package com.example.demo.user.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class LoginAttemptServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private LoginAttemptService loginAttemptService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        loginAttemptService = new LoginAttemptService(redisTemplate);
    }

    @Test
    void 잠금_상태가_아니면_checkNotLocked는_통과한다() {
        when(redisTemplate.hasKey("login-locked:tester01")).thenReturn(false);

        assertThatCode(() -> loginAttemptService.checkNotLocked("tester01"))
                .doesNotThrowAnyException();
    }

    @Test
    void 잠금_상태면_남은_시간을_포함한_예외가_발생한다() {
        when(redisTemplate.hasKey("login-locked:tester01")).thenReturn(true);
        when(redisTemplate.getExpire("login-locked:tester01")).thenReturn(700L);

        assertThatThrownBy(() -> loginAttemptService.checkNotLocked("tester01"))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("12분")
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);
    }

    @Test
    void 남은_시간이_1분_미만이어도_1분으로_표기한다() {
        when(redisTemplate.hasKey("login-locked:tester01")).thenReturn(true);
        when(redisTemplate.getExpire("login-locked:tester01")).thenReturn(30L);

        assertThatThrownBy(() -> loginAttemptService.checkNotLocked("tester01"))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("1분");
    }

    @Test
    void 실패가_5회_미만이면_예외없이_카운트만_한다() {
        when(valueOperations.increment("login-fail:tester01")).thenReturn(3L);

        assertThatCode(() -> loginAttemptService.recordFailure("tester01"))
                .doesNotThrowAnyException();

        verify(redisTemplate).expire("login-fail:tester01", 30, TimeUnit.MINUTES);
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void 다섯번째_실패하면_1단계_5분_잠금이_설정되고_예외가_발생한다() {
        when(valueOperations.increment("login-fail:tester01")).thenReturn(5L);
        when(valueOperations.setIfAbsent("login-locked:tester01", "true", 5L, TimeUnit.MINUTES)).thenReturn(true);
        when(valueOperations.increment("login-lock-level:tester01")).thenReturn(1L);

        assertThatThrownBy(() -> loginAttemptService.recordFailure("tester01"))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("5분")
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);

        verify(valueOperations).setIfAbsent("login-locked:tester01", "true", 5L, TimeUnit.MINUTES);
        verify(redisTemplate).expire("login-lock-level:tester01", 24, TimeUnit.HOURS);
        // tier 1 duration matches the provisional TTL already set by setIfAbsent, so no correction call is made.
        verify(redisTemplate, never()).expire(eq("login-locked:tester01"), anyLong(), eq(TimeUnit.MINUTES));
        verify(redisTemplate).delete("login-fail:tester01");
    }

    @Test
    void 두번째_잠금은_15분이다() {
        when(valueOperations.increment("login-fail:tester01")).thenReturn(5L);
        when(valueOperations.setIfAbsent("login-locked:tester01", "true", 5L, TimeUnit.MINUTES)).thenReturn(true);
        when(valueOperations.increment("login-lock-level:tester01")).thenReturn(2L);

        assertThatThrownBy(() -> loginAttemptService.recordFailure("tester01"))
                .hasMessageContaining("15분");

        verify(redisTemplate).expire("login-locked:tester01", 15L, TimeUnit.MINUTES);
    }

    @Test
    void 세번째_잠금은_30분이다() {
        when(valueOperations.increment("login-fail:tester01")).thenReturn(5L);
        when(valueOperations.setIfAbsent("login-locked:tester01", "true", 5L, TimeUnit.MINUTES)).thenReturn(true);
        when(valueOperations.increment("login-lock-level:tester01")).thenReturn(3L);

        assertThatThrownBy(() -> loginAttemptService.recordFailure("tester01"))
                .hasMessageContaining("30분");

        verify(redisTemplate).expire("login-locked:tester01", 30L, TimeUnit.MINUTES);
    }

    @Test
    void 네번째_이후_잠금도_30분을_유지한다() {
        when(valueOperations.increment("login-fail:tester01")).thenReturn(5L);
        when(valueOperations.setIfAbsent("login-locked:tester01", "true", 5L, TimeUnit.MINUTES)).thenReturn(true);
        when(valueOperations.increment("login-lock-level:tester01")).thenReturn(4L);

        assertThatThrownBy(() -> loginAttemptService.recordFailure("tester01"))
                .hasMessageContaining("30분");

        verify(redisTemplate).expire("login-locked:tester01", 30L, TimeUnit.MINUTES);
    }

    @Test
    void 동시_요청이_이미_잠금을_설정했으면_레벨을_증가시키지_않고_기존_잠금_시간을_반환한다() {
        when(valueOperations.increment("login-fail:tester01")).thenReturn(5L);
        // Another concurrent request already won the race and created the lock key.
        when(valueOperations.setIfAbsent("login-locked:tester01", "true", 5L, TimeUnit.MINUTES)).thenReturn(false);
        when(redisTemplate.getExpire("login-locked:tester01")).thenReturn(890L);

        assertThatThrownBy(() -> loginAttemptService.recordFailure("tester01"))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("15분")
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);

        verify(valueOperations, never()).increment("login-lock-level:tester01");
        verify(redisTemplate, never()).expire(eq("login-lock-level:tester01"), anyLong(), eq(TimeUnit.HOURS));
        verify(redisTemplate, never()).expire(eq("login-locked:tester01"), anyLong(), eq(TimeUnit.MINUTES));
        verify(redisTemplate).delete("login-fail:tester01");
    }

    @Test
    void recordSuccess는_실패_카운터와_잠금_단계를_삭제한다() {
        loginAttemptService.recordSuccess("tester01");

        verify(redisTemplate).delete("login-fail:tester01");
        verify(redisTemplate).delete("login-lock-level:tester01");
        verify(redisTemplate, never()).delete("login-locked:tester01");
    }

    @Test
    void clearLockState는_모든_키를_삭제한다() {
        loginAttemptService.clearLockState("tester01");

        verify(redisTemplate).delete("login-fail:tester01");
        verify(redisTemplate).delete("login-lock-level:tester01");
        verify(redisTemplate).delete("login-locked:tester01");
    }
}
