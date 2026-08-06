package com.example.demo.global.crypto;

import org.springframework.stereotype.Component;

/**
 * PiiCipher를 감싸는 얇은 Spring 빈. 서비스 계층(UserService, AuthService 등)에
 * 주입/모킹하기 위한 용도 — 실제 암/복호화 로직은 PiiCipher에 있다.
 */
@Component
public class PiiCryptoService {

    public String encrypt(String plaintext) {
        return PiiCipher.encrypt(plaintext);
    }

    public String decrypt(String stored) {
        return PiiCipher.decrypt(stored);
    }

    public String hashForSearch(String plaintext) {
        return PiiCipher.hashForSearch(plaintext);
    }
}
