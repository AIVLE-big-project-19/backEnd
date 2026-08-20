package com.example.demo.user.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class LoginAttemptService {

    private static final String FAIL_KEY_PREFIX = "login-fail:";
    private static final String LOCK_LEVEL_KEY_PREFIX = "login-lock-level:";
    private static final String LOCKED_KEY_PREFIX = "login-locked:";
    private static final int MAX_ATTEMPTS = 5;
    private static final long[] LOCK_MINUTES = {5, 15, 30};
    private static final long FAIL_WINDOW_MINUTES = 30;
    private static final long LOCK_LEVEL_RETENTION_HOURS = 24;

    private final StringRedisTemplate redisTemplate;

    public LoginAttemptService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void checkNotLocked(String loginId) {
        String lockedKey = LOCKED_KEY_PREFIX + loginId;

        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockedKey))) {
            throw lockedException(remainingMinutesCeil(redisTemplate.getExpire(lockedKey)));
        }
    }

    public void recordFailure(String loginId) {
        String failKey = FAIL_KEY_PREFIX + loginId;

        Long attempts = redisTemplate.opsForValue().increment(failKey);
        redisTemplate.expire(failKey, FAIL_WINDOW_MINUTES, TimeUnit.MINUTES);

        if (attempts != null && attempts >= MAX_ATTEMPTS) {
            long lockMinutes = lockAndGetMinutes(loginId);
            redisTemplate.delete(failKey);
            throw lockedException(lockMinutes);
        }
    }

    public void recordSuccess(String loginId) {
        redisTemplate.delete(FAIL_KEY_PREFIX + loginId);
        redisTemplate.delete(LOCK_LEVEL_KEY_PREFIX + loginId);
    }

    public void clearLockState(String loginId) {
        redisTemplate.delete(FAIL_KEY_PREFIX + loginId);
        redisTemplate.delete(LOCK_LEVEL_KEY_PREFIX + loginId);
        redisTemplate.delete(LOCKED_KEY_PREFIX + loginId);
    }

    private long lockAndGetMinutes(String loginId) {
        String lockedKey = LOCKED_KEY_PREFIX + loginId;
        String levelKey = LOCK_LEVEL_KEY_PREFIX + loginId;

        // 참고: SET NX로 잠금을 획득한 요청만 단계를 올려 동시 실패 요청이 잠금 시간을 중복 증가시키지 않게 한다.
        boolean acquiredLock = Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(lockedKey, "true", LOCK_MINUTES[0], TimeUnit.MINUTES));

        if (!acquiredLock) {
            // 참고: 다른 요청이 먼저 잠갔다면 단계를 변경하지 않고 해당 잠금의 남은 시간을 반환한다.
            return remainingMinutesCeil(redisTemplate.getExpire(lockedKey));
        }

        Long level = redisTemplate.opsForValue().increment(levelKey);
        redisTemplate.expire(levelKey, LOCK_LEVEL_RETENTION_HOURS, TimeUnit.HOURS);

        int index = (int) Math.min(level == null ? 1L : level, LOCK_MINUTES.length) - 1;
        long lockMinutes = LOCK_MINUTES[index];

        if (lockMinutes != LOCK_MINUTES[0]) {
            // 참고: 최초 잠금에 설정한 1단계 TTL을 실제 누적 잠금 단계의 시간으로 교체한다.
            redisTemplate.expire(lockedKey, lockMinutes, TimeUnit.MINUTES);
        }

        return lockMinutes;
    }

    private long remainingMinutesCeil(Long remainingSeconds) {
        return (remainingSeconds == null || remainingSeconds <= 0)
                ? 1
                : (remainingSeconds + 59) / 60;
    }

    private CustomException lockedException(long remainingMinutes) {
        return new CustomException(
                ErrorCode.ACCOUNT_LOCKED,
                "로그인 시도 횟수를 초과하여 계정이 일시적으로 잠겼습니다. " + remainingMinutes + "분 후 다시 시도해주세요."
        );
    }
}
