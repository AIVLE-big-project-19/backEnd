package com.example.demo.user.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.dto.TermsResponse;
import com.example.demo.user.entity.ConsentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TermsService {

    private final String termsVersion;
    private final Map<ConsentType, String> contentCache = new ConcurrentHashMap<>();

    public TermsService(@Value("${terms.version}") String termsVersion) {
        this.termsVersion = termsVersion;
    }

    public TermsResponse getTerms(String rawType) {
        ConsentType type = parseType(rawType);
        String content = contentCache.computeIfAbsent(type, this::loadContent);

        return TermsResponse.builder()
                .type(type.name())
                .version(termsVersion)
                .content(content)
                .build();
    }

    private ConsentType parseType(String rawType) {
        ConsentType type;
        try {
            type = ConsentType.valueOf(rawType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.TERMS_NOT_FOUND);
        }

        if (type == ConsentType.MARKETING) {
            throw new CustomException(ErrorCode.TERMS_NOT_FOUND);
        }

        return type;
    }

    private String loadContent(ConsentType type) {
        try {
            return new ClassPathResource("terms/" + type.name() + ".md")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.TERMS_NOT_FOUND);
        }
    }
}
