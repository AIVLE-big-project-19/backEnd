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

        // Atomically claim the lock transition with SET NX. Only the request that actually
        // creates the lock key may advance the lock-level counter; concurrent requests that
        // lose this race must not each bump the level, or a single burst of failures could
        // jump straight to a longer tier instead of always starting at tier 1.
        boolean acquiredLock = Boolean.TRUE.equals(
                redisTemplate.opsForValue().setIfAbsent(lockedKey, "true", LOCK_MINUTES[0], TimeUnit.MINUTES));

        if (!acquiredLock) {
            // Another concurrent request already locked this account moments ago; report
            // whatever duration it set instead of touching the shared level counter.
            return remainingMinutesCeil(redisTemplate.getExpire(lockedKey));
        }

        Long level = redisTemplate.opsForValue().increment(levelKey);
        redisTemplate.expire(levelKey, LOCK_LEVEL_RETENTION_HOURS, TimeUnit.HOURS);

        int index = (int) Math.min(level == null ? 1L : level, LOCK_MINUTES.length) - 1;
        long lockMinutes = LOCK_MINUTES[index];

        if (lockMinutes != LOCK_MINUTES[0]) {
            // The provisional tier-1 TTL set above doesn't match the real tier; correct it.
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
