package com.example.demo.global.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * email/name 등 개인정보 컬럼에 공용으로 붙이는 JPA 컨버터.
 * Hibernate가 Spring 컨텍스트 범위(@DataJpaTest 슬라이스 포함)와 무관하게
 * 직접 인스턴스화하므로 Spring 빈 주입을 쓰지 않고 PiiCipher를 바로 호출한다.
 */
@Converter
public class PiiCryptoConverter implements AttributeConverter<String, String> {

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return PiiCipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return PiiCipher.decrypt(dbData);
    }
}
