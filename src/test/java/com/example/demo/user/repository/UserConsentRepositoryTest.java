package com.example.demo.user.repository;

import com.example.demo.global.config.JpaAuditingConfig;
import com.example.demo.user.entity.ConsentType;
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserConsent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never"
})
@Import(JpaAuditingConfig.class)
class UserConsentRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserConsentRepository userConsentRepository;

    private User newUser(String email) {
        return userRepository.save(User.builder()
                .email(email)
                .name("동의테스터")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build());
    }

    @Test
    void 같은_타입의_행이_여러_개면_가장_최근_행을_반환한다() {
        User user = newUser("consent@test.com");

        userConsentRepository.save(UserConsent.builder()
                .user(user).consentType(ConsentType.MARKETING).agreed(false).termsVersion("1.0").build());
        userConsentRepository.save(UserConsent.builder()
                .user(user).consentType(ConsentType.MARKETING).agreed(true).termsVersion("1.0").build());

        Optional<UserConsent> latest = userConsentRepository
                .findTopByUserAndConsentTypeOrderByIdDesc(user, ConsentType.MARKETING);

        assertThat(latest).isPresent();
        assertThat(latest.get().isAgreed()).isTrue();
    }

    @Test
    void 타입별로_최신_행을_독립적으로_조회한다() {
        User user = newUser("consent-per-type@test.com");

        userConsentRepository.save(UserConsent.builder()
                .user(user).consentType(ConsentType.TERMS).agreed(false).termsVersion("1.0").build());
        userConsentRepository.save(UserConsent.builder()
                .user(user).consentType(ConsentType.TERMS).agreed(true).termsVersion("1.0").build());

        userConsentRepository.save(UserConsent.builder()
                .user(user).consentType(ConsentType.MARKETING).agreed(true).termsVersion("1.0").build());
        userConsentRepository.save(UserConsent.builder()
                .user(user).consentType(ConsentType.MARKETING).agreed(false).termsVersion("1.0").build());

        Optional<UserConsent> latestTerms = userConsentRepository
                .findTopByUserAndConsentTypeOrderByIdDesc(user, ConsentType.TERMS);
        Optional<UserConsent> latestMarketing = userConsentRepository
                .findTopByUserAndConsentTypeOrderByIdDesc(user, ConsentType.MARKETING);

        assertThat(latestTerms).isPresent();
        assertThat(latestTerms.get().isAgreed()).isTrue();

        assertThat(latestMarketing).isPresent();
        assertThat(latestMarketing.get().isAgreed()).isFalse();
    }

    @Test
    void 기록이_없으면_빈_Optional을_반환한다() {
        User user = newUser("no-consent@test.com");

        Optional<UserConsent> latest = userConsentRepository
                .findTopByUserAndConsentTypeOrderByIdDesc(user, ConsentType.PRIVACY);

        assertThat(latest).isEmpty();
    }
}
