package com.example.demo.user.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

@Service
public class EmailVerificationService {

    private static final String CODE_KEY_PREFIX = "email-code:";
    private static final String VERIFIED_KEY_PREFIX = "email-verified:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final MailSender mailSender;

    public EmailVerificationService(StringRedisTemplate redisTemplate, MailSender mailSender) {
        this.redisTemplate = redisTemplate;
        this.mailSender = mailSender;
    }

    public void sendCode(String email) {
        String codeKey = CODE_KEY_PREFIX + email;

        if (Boolean.TRUE.equals(redisTemplate.hasKey(codeKey))) {
            throw new CustomException(ErrorCode.EMAIL_CODE_COOLDOWN);
        }

        String code = generateCode();
        redisTemplate.opsForValue().set(codeKey, code, 5, TimeUnit.MINUTES);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(email);
        message.setSubject("[인증번호] 회원가입 이메일 인증");
        message.setText("인증번호는 " + code + " 입니다. 5분 이내에 입력해주세요.");
        mailSender.send(message);
    }

    public void verifyCode(String email, String code) {
        String codeKey = CODE_KEY_PREFIX + email;
        String savedCode = redisTemplate.opsForValue().get(codeKey);

        if (savedCode == null || !savedCode.equals(code)) {
            throw new CustomException(ErrorCode.INVALID_VERIFICATION_CODE);
        }

        redisTemplate.opsForValue().set(VERIFIED_KEY_PREFIX + email, "true", 30, TimeUnit.MINUTES);
        redisTemplate.delete(codeKey);
    }

    public boolean isVerified(String email) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(VERIFIED_KEY_PREFIX + email));
    }

    public void clearVerified(String email) {
        redisTemplate.delete(VERIFIED_KEY_PREFIX + email);
    }

    private String generateCode() {
        int code = 100000 + RANDOM.nextInt(900000);
        return String.valueOf(code);
    }
}
