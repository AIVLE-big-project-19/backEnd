package com.example.demo.user.repository;

import com.example.demo.global.config.JpaAuditingConfig;
import com.example.demo.global.util.EmailHasher;
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.User;
import com.example.demo.user.service.PiiMigrationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
class UserPiiEncryptionRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void 저장한_이메일과_이름을_다시_읽으면_평문_그대로다() {
        User saved = userRepository.save(User.builder()
                .email("pii-test@example.com")
                .emailHash(EmailHasher.hash("pii-test@example.com"))
                .name("암호화테스터")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build());

        User reloaded = userRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getEmail()).isEqualTo("pii-test@example.com");
        assertThat(reloaded.getName()).isEqualTo("암호화테스터");
    }

    @Test
    void emailHash로_조회하면_해당_사용자를_찾는다() {
        userRepository.save(User.builder()
                .email("hash-lookup@example.com")
                .emailHash(EmailHasher.hash("hash-lookup@example.com"))
                .name("해시조회테스터")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build());

        Optional<User> found = userRepository.findByEmailHash(EmailHasher.hash("hash-lookup@example.com"));

        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("hash-lookup@example.com");
    }

    @Test
    void emailHash가_있으면_existsByEmailHash가_true를_반환한다() {
        userRepository.save(User.builder()
                .email("exists-check@example.com")
                .emailHash(EmailHasher.hash("exists-check@example.com"))
                .name("존재확인테스터")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build());

        assertThat(userRepository.existsByEmailHash(EmailHasher.hash("exists-check@example.com"))).isTrue();
        assertThat(userRepository.existsByEmailHash(EmailHasher.hash("no-such-user@example.com"))).isFalse();
    }

    @Test
    void emailHash가_없는_행은_마이그레이션_대상_조회에_포함된다() {
        userRepository.save(User.builder()
                .email("pending@example.com")
                .name("미마이그레이션테스터")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build());

        assertThat(userRepository.findByEmailHashIsNull()).hasSize(1);
    }

    @Test
    void 컨버터를_우회해_삽입한_레거시_평문_행이_마이그레이션_후_실제로_암호화된다() {
        entityManager.createNativeQuery(
                        "INSERT INTO users (login_id, email, email_hash, name, provider, role, created_at, updated_at) " +
                                "VALUES (:loginId, :email, NULL, :name, 'LOCAL', 'USER', NOW(), NOW())")
                .setParameter("loginId", "legacyuser01")
                .setParameter("email", "legacy-plaintext@example.com")
                .setParameter("name", "레거시평문유저")
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        User legacy = userRepository.findAll().stream()
                .filter(u -> "legacyuser01".equals(u.getLoginId()))
                .findFirst()
                .orElseThrow();
        assertThat(legacy.getEmail()).isEqualTo("legacy-plaintext@example.com");
        assertThat(legacy.getName()).isEqualTo("레거시평문유저");

        new PiiMigrationService(userRepository).migratePendingUsers();
        entityManager.flush();
        entityManager.clear();

        Object rawEmail = entityManager.createNativeQuery(
                        "SELECT email FROM users WHERE login_id = :loginId")
                .setParameter("loginId", "legacyuser01")
                .getSingleResult();
        assertThat((String) rawEmail).startsWith("ENC:");

        User reloaded = userRepository.findById(legacy.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo("legacy-plaintext@example.com");
    }
}
