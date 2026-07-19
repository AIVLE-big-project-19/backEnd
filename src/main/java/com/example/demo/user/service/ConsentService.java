package com.example.demo.user.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.dto.ConsentStatusResponse;
import com.example.demo.user.entity.ConsentType;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserConsent;
import com.example.demo.user.repository.UserConsentRepository;
import com.example.demo.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Service
public class ConsentService {

    private final UserConsentRepository userConsentRepository;
    private final UserRepository userRepository;
    private final String termsVersion;

    public ConsentService(
            UserConsentRepository userConsentRepository,
            UserRepository userRepository,
            @Value("${terms.version}") String termsVersion
    ) {
        this.userConsentRepository = userConsentRepository;
        this.userRepository = userRepository;
        this.termsVersion = termsVersion;
    }

    public void recordSignupConsents(User user, boolean marketingAgreed) {
        userConsentRepository.saveAll(List.of(
                consentRow(user, ConsentType.TERMS, true),
                consentRow(user, ConsentType.PRIVACY, true),
                consentRow(user, ConsentType.MARKETING, marketingAgreed)
        ));
    }

    public ConsentStatusResponse getConsentStatus(Long userId) {
        User user = findUser(userId);

        List<ConsentStatusResponse.ConsentItem> items = Arrays.stream(ConsentType.values())
                .map(type -> toItem(type,
                        userConsentRepository.findTopByUserAndConsentTypeOrderByIdDesc(user, type).orElse(null)))
                .toList();

        return ConsentStatusResponse.builder().consents(items).build();
    }

    @Transactional
    public ConsentStatusResponse.ConsentItem updateMarketingConsent(Long userId, boolean agreed) {
        User user = findUser(userId);

        UserConsent saved = userConsentRepository.save(consentRow(user, ConsentType.MARKETING, agreed));

        return toItem(ConsentType.MARKETING, saved);
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }

    private UserConsent consentRow(User user, ConsentType type, boolean agreed) {
        return UserConsent.builder()
                .user(user)
                .consentType(type)
                .agreed(agreed)
                .termsVersion(termsVersion)
                .build();
    }

    private ConsentStatusResponse.ConsentItem toItem(ConsentType type, UserConsent consent) {
        if (consent == null) {
            return ConsentStatusResponse.ConsentItem.builder()
                    .type(type.name())
                    .build();
        }

        return ConsentStatusResponse.ConsentItem.builder()
                .type(type.name())
                .agreed(consent.isAgreed())
                .version(consent.getTermsVersion())
                .agreedAt(consent.getCreatedAt())
                .build();
    }
}
