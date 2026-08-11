# User email/name AES-256-GCM 컬럼 암호화 (재시도) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `User.email`/`User.name`을 DB 컬럼 레벨에서 AES-256-GCM으로 암호화해서, DB 유출/백업 노출 시 평문이 그대로 남지 않게 한다.

**Architecture:** JPA `AttributeConverter`(`PiiCryptoConverter`)로 엔티티 필드 레벨에서 암/복호화를 투명하게 처리한다. 애플리케이션 코드(`UserService`, `AuthService` 등)는 지금처럼 평문 문자열을 다루고, DB에 쓰고 읽는 시점에만 자동 변환된다. `email`은 정확 일치 조회(로그인 아이디/비번 찾기, 구글 자동가입 매칭)가 필요해서 HMAC-SHA256 검색용 해시 컬럼(`emailHash`)을 추가하고, unique 제약을 `email`→`emailHash`로 옮긴다. 컨버터는 복호화 실패(또는 `ENC:` 접두사가 없는 레거시 평문) 시 예외를 던지지 않고 원문을 그대로 반환하며, 앱 기동 시 `ApplicationRunner`가 기존 평문 행을 자동으로 백필(암호화+해시 채움)한다.

**Tech Stack:** Spring Boot 4.1 / Java 17 / Spring Data JPA / MySQL(운영), H2(`@DataJpaTest`) / JUnit5 + Mockito + AssertJ / `javax.crypto`(JDK 내장, 별도 의존성 추가 불필요)

## Global Constraints

- 크립토 관련 클래스(`PiiCipher`, `EmailHasher`)는 **Spring 빈이 아닌 순수 static 유틸리티**로 작성한다 — JPA `@Converter`는 Hibernate가 Spring 컨텍스트 범위와 무관하게 직접 인스턴스화하므로, `@Value` 등 DI에 의존하면 `@DataJpaTest` 슬라이스에서 `BeanCreationException`이 난다(지난 시도의 실제 장애 원인). `HashUtil`(`src/main/java/com/example/demo/global/util/HashUtil.java`)과 동일한 무-DI 스타일을 따른다.
- **복호화는 절대 예외를 던지지 않는다.** 저장된 값이 `ENC:` 접두사로 시작하지 않거나(레거시 평문) 복호화에 실패하면, 원문 그대로 반환한다. 이전 배포 장애(500 에러)가 정확히 "예외를 던져서 요청 전체가 죽는" 패턴이었으므로, 이번엔 이 폴백이 필수다.
- 키는 `PII_ENCRYPTION_KEY` 환경변수로 관리한다. 읽는 순서는 `System.getProperty` → `System.getenv` → dev 기본값, `JWT_SECRET`과 동일한 패턴(`DemoApplication`의 dotenv 로딩).
- 기존 `UserRepository.findByEmail`/`existsByEmail`을 호출하는 곳(현재 `UserService` 2곳, `AuthService` 1곳)은 **전부** `findByEmailHash`/`existsByEmailHash`로 옮긴다. 예전 메서드는 삭제한다(암호문 컬럼을 대상으로 한 무의미한 exact-match만 남기지 않는다).
- 이메일 해시는 **소문자로 정규화 + 트림한 뒤** 계산한다 — 현재 MySQL 기본 콜레이션이 대소문자를 구분하지 않아 `findByEmail`이 사실상 대소문자 무관하게 동작해왔는데, 해시 exact-match로 바꾸면 이 동작이 깨지므로 정규화로 유지한다.
- DB 스키마는 이 프로젝트의 기존 방식대로 `ddl-auto: update`가 자동 반영한다(Flyway/Liquibase 없음) — 신규 컬럼(`emailHash`) 추가와 `email`/`name` 컬럼 길이 확장은 여기 포함된다. 기존 행의 데이터(값 자체) 백필은 별도 `ApplicationRunner`가 기동 시 처리한다.
- 작업은 `main`에서 새로 브랜치를 파서 진행한다(예: `feature/pii-column-encryption-v2`). 현재 작업 브랜치(`fix/inquiry-mail-log-email-masking`)는 관련 없는 변경사항이 섞여 있으니 그 위에 얹지 않는다.
- 각 태스크 끝에 커밋한다. 마지막 태스크에서 PR을 연다.

---

## Setup: 브랜치 생성

- [ ] **작업 브랜치 생성**

```bash
git fetch origin main
git switch -c feature/pii-column-encryption-v2 origin/main
```

---

### Task 1: PiiCipher — AES-256-GCM 암/복호화 핵심 로직

**Files:**
- Create: `src/main/java/com/example/demo/global/crypto/PiiCipher.java`
- Test: `src/test/java/com/example/demo/global/crypto/PiiCipherTest.java`
- Modify: `src/main/java/com/example/demo/DemoApplication.java`

**Interfaces:**
- Produces: `PiiCipher.encrypt(String plaintext) -> String`(package-private, `null` 입력 시 `null` 반환, 성공 시 `"ENC:" + Base64(IV+ciphertext)` 형태), `PiiCipher.decrypt(String stored) -> String`(package-private, `null` 입력 시 `null`, `ENC:` 접두사가 없거나 복호화 실패 시 입력값 그대로 반환)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/demo/global/crypto/PiiCipherTest.java`:

```java
package com.example.demo.global.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiCipherTest {

    @Test
    void 암호화한_값을_복호화하면_원문과_같다() {
        String plaintext = "user@example.com";

        String encrypted = PiiCipher.encrypt(plaintext);
        String decrypted = PiiCipher.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void 같은_평문도_암호화할때마다_다른_값이_나온다() {
        String plaintext = "user@example.com";

        String first = PiiCipher.encrypt(plaintext);
        String second = PiiCipher.encrypt(plaintext);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void 암호문은_ENC_접두사로_시작한다() {
        String encrypted = PiiCipher.encrypt("user@example.com");

        assertThat(encrypted).startsWith("ENC:");
    }

    @Test
    void ENC_접두사가_없는_레거시_평문은_그대로_반환한다() {
        String legacyPlaintext = "legacy-user@example.com";

        String result = PiiCipher.decrypt(legacyPlaintext);

        assertThat(result).isEqualTo(legacyPlaintext);
    }

    @Test
    void 손상된_암호문은_복호화_실패시_원문_그대로_반환한다() {
        String corrupted = "ENC:not-a-valid-base64-ciphertext!!";

        String result = PiiCipher.decrypt(corrupted);

        assertThat(result).isEqualTo(corrupted);
    }

    @Test
    void null을_암호화하면_null을_반환한다() {
        assertThat(PiiCipher.encrypt(null)).isNull();
    }

    @Test
    void null을_복호화하면_null을_반환한다() {
        assertThat(PiiCipher.decrypt(null)).isNull();
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.example.demo.global.crypto.PiiCipherTest"`
Expected: FAIL — `PiiCipher` 클래스가 없어서 컴파일 에러

- [ ] **Step 3: 최소 구현 작성**

`src/main/java/com/example/demo/global/crypto/PiiCipher.java`:

```java
package com.example.demo.global.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * User.email/name 컬럼 암호화 핵심 로직.
 * Hibernate가 Spring 컨텍스트와 무관하게 JPA Converter를 직접 인스턴스화하므로
 * Spring DI에 기대지 않는 정적 유틸리티로 작성한다(HashUtil과 동일한 스타일).
 */
final class PiiCipher {

    private static final String ENC_PREFIX = "ENC:";
    private static final String CIPHER_ALGO = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private PiiCipher() {
    }

    static String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] ivAndCiphertext = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, ivAndCiphertext, 0, iv.length);
            System.arraycopy(ciphertext, 0, ivAndCiphertext, iv.length, ciphertext.length);

            return ENC_PREFIX + Base64.getEncoder().encodeToString(ivAndCiphertext);
        } catch (Exception e) {
            throw new IllegalStateException("개인정보 암호화에 실패했습니다.", e);
        }
    }

    /**
     * 저장된 값이 ENC: 접두사로 시작하지 않거나(마이그레이션 전 레거시 평문) 복호화에
     * 실패하면 예외를 던지지 않고 원문 그대로 반환한다. 지난 배포 장애가 정확히
     * "복호화 실패 시 예외가 요청 전체를 500으로 죽이는" 패턴이었기 때문에, 이 폴백은
     * 선택이 아니라 필수 안전장치다.
     */
    static String decrypt(String stored) {
        if (stored == null || !stored.startsWith(ENC_PREFIX)) {
            return stored;
        }

        try {
            byte[] ivAndCiphertext = Base64.getDecoder().decode(stored.substring(ENC_PREFIX.length()));
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[ivAndCiphertext.length - GCM_IV_LENGTH];
            System.arraycopy(ivAndCiphertext, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(ivAndCiphertext, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return stored;
        }
    }

    static SecretKeySpec secretKey() {
        String secret = System.getProperty("PII_ENCRYPTION_KEY", System.getenv("PII_ENCRYPTION_KEY"));
        if (secret == null || secret.isBlank()) {
            secret = "dev-only-pii-key-please-override-in-real-env";
        }
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
```

`src/main/java/com/example/demo/DemoApplication.java` 수정 — `PII_ENCRYPTION_KEY`를 dotenv 로딩 목록에 추가:

```java
		setIfPresent(dotenv, "JWT_SECRET");
		setIfPresent(dotenv, "PII_ENCRYPTION_KEY");
		setIfPresent(dotenv, "GOOGLE_CLIENT_ID");
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.example.demo.global.crypto.PiiCipherTest"`
Expected: PASS (7 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/demo/global/crypto/PiiCipher.java src/test/java/com/example/demo/global/crypto/PiiCipherTest.java src/main/java/com/example/demo/DemoApplication.java
git commit -m "feat: AES-256-GCM 암/복호화 핵심 로직(PiiCipher) 추가"
```

---

### Task 2: EmailHasher — 조회용 결정적 해시

**Files:**
- Create: `src/main/java/com/example/demo/global/util/EmailHasher.java`
- Test: `src/test/java/com/example/demo/global/util/EmailHasherTest.java`

**Interfaces:**
- Consumes: 없음(독립 유틸)
- Produces: `EmailHasher.hash(String email) -> String`(public, 64자 hex, 같은 이메일은 대소문자·앞뒤공백 무관하게 항상 같은 값)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/demo/global/util/EmailHasherTest.java`:

```java
package com.example.demo.global.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailHasherTest {

    @Test
    void 같은_이메일은_항상_같은_해시값을_만든다() {
        String first = EmailHasher.hash("user@example.com");
        String second = EmailHasher.hash("user@example.com");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void 다른_이메일은_다른_해시값을_만든다() {
        String first = EmailHasher.hash("user1@example.com");
        String second = EmailHasher.hash("user2@example.com");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void 대소문자만_다른_이메일은_같은_해시값을_만든다() {
        String lower = EmailHasher.hash("user@example.com");
        String upper = EmailHasher.hash("USER@EXAMPLE.COM");

        assertThat(lower).isEqualTo(upper);
    }

    @Test
    void 앞뒤_공백만_다른_이메일은_같은_해시값을_만든다() {
        String trimmed = EmailHasher.hash("user@example.com");
        String padded = EmailHasher.hash("  user@example.com  ");

        assertThat(trimmed).isEqualTo(padded);
    }

    @Test
    void 해시값은_64자리_16진수_문자열이다() {
        String hash = EmailHasher.hash("user@example.com");

        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.example.demo.global.util.EmailHasherTest"`
Expected: FAIL — `EmailHasher` 클래스가 없어서 컴파일 에러

- [ ] **Step 3: 최소 구현 작성**

`src/main/java/com/example/demo/global/util/EmailHasher.java`:

```java
package com.example.demo.global.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 이메일 조회용 결정적 해시(HMAC-SHA256). email 컬럼 자체는 AES-GCM으로 암호화되어
 * 매번 다른 값이 나오므로 exact-match 조회가 불가능하다 — 별도 emailHash 컬럼으로
 * 조회한다. PiiCipher와 동일하게 Spring DI 없이 동작해야 하므로 완전히 독립된
 * 정적 유틸리티로 둔다(약간의 키 로딩 코드 중복은 이 결합을 피하기 위한 의도적 선택).
 */
public final class EmailHasher {

    private static final String HMAC_ALGO = "HmacSHA256";

    private EmailHasher() {
    }

    public static String hash(String email) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secretKeyBytes(), HMAC_ALGO));
            byte[] hashed = mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("이메일 해시 생성에 실패했습니다.", e);
        }
    }

    private static byte[] secretKeyBytes() {
        String secret = System.getProperty("PII_ENCRYPTION_KEY", System.getenv("PII_ENCRYPTION_KEY"));
        if (secret == null || secret.isBlank()) {
            secret = "dev-only-pii-key-please-override-in-real-env";
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.example.demo.global.util.EmailHasherTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/demo/global/util/EmailHasher.java src/test/java/com/example/demo/global/util/EmailHasherTest.java
git commit -m "feat: 이메일 조회용 결정적 해시(EmailHasher) 추가"
```

---

### Task 3: User 엔티티 암호화 적용 + UserRepository 해시 조회 메서드

**Files:**
- Create: `src/main/java/com/example/demo/global/crypto/PiiCryptoConverter.java`
- Test: `src/test/java/com/example/demo/user/repository/UserPiiEncryptionRepositoryTest.java`
- Modify: `src/main/java/com/example/demo/user/entity/User.java`
- Modify: `src/main/java/com/example/demo/user/repository/UserRepository.java`

**Interfaces:**
- Consumes: `PiiCipher.encrypt`/`decrypt`(Task 1), `EmailHasher.hash`(Task 2)
- Produces: `User.getEmailHash()`/`setEmailHash(String)`, `UserRepository.findByEmailHash(String) -> Optional<User>`, `UserRepository.existsByEmailHash(String) -> boolean`, `UserRepository.findByEmailHashIsNull() -> List<User>`(Task 6에서 마이그레이션 러너가 사용)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/demo/user/repository/UserPiiEncryptionRepositoryTest.java`:

```java
package com.example.demo.user.repository;

import com.example.demo.global.config.JpaAuditingConfig;
import com.example.demo.global.util.EmailHasher;
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.User;
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
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.repository.UserPiiEncryptionRepositoryTest"`
Expected: FAIL — `User.emailHash`/`UserRepository.findByEmailHash` 등이 없어서 컴파일 에러

- [ ] **Step 3: 최소 구현 작성**

`src/main/java/com/example/demo/global/crypto/PiiCryptoConverter.java`:

```java
package com.example.demo.global.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

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
```

`src/main/java/com/example/demo/user/entity/User.java` 수정 — `email`/`name` 필드에 컨버터 적용, `emailHash` 필드 추가:

```java
package com.example.demo.user.entity;

import com.example.demo.global.crypto.PiiCryptoConverter;
import com.example.demo.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 50)
    private String loginId;

    // AES-256-GCM 암호문 저장. 같은 평문도 매번 다른 암호문이 나오므로 이 컬럼엔
    // unique 제약을 걸 수 없다 — unique 제약은 emailHash로 옮겼다.
    // 길이 여유: IV(12B)+평문+GCM태그(16B)를 Base64 인코딩하면 원문의 약 1.4배 +
    // "ENC:" 접두사 4자. 이메일/이름 모두 255면 충분히 여유롭다.
    @Column(nullable = false, length = 255)
    @Convert(converter = PiiCryptoConverter.class)
    private String email;

    // HMAC-SHA256(정규화된 이메일) — 정확 일치 조회 전용. nullable: 마이그레이션 전
    // 행은 일시적으로 null이며(PiiMigrationRunner가 채움), unique 컬럼에서 NULL은
    // 여러 개 허용되므로 제약 위반 없이 공존 가능하다.
    @Column(unique = true, length = 64)
    private String emailHash;

    @Column(length = 100)
    private String password;

    @Column(nullable = false, length = 255)
    @Convert(converter = PiiCryptoConverter.class)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Provider provider;

    @Column(length = 100)
    private String providerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

}
```

`src/main/java/com/example/demo/user/repository/UserRepository.java` 수정 — 해시 기반 조회 메서드로 교체:

```java
package com.example.demo.user.repository;

import com.example.demo.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByLoginId(String loginId);

    Optional<User> findByEmailHash(String emailHash);

    boolean existsByLoginId(String loginId);

    boolean existsByEmailHash(String emailHash);

    List<User> findByEmailHashIsNull();

}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.repository.UserPiiEncryptionRepositoryTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/demo/global/crypto/PiiCryptoConverter.java src/main/java/com/example/demo/user/entity/User.java src/main/java/com/example/demo/user/repository/UserRepository.java src/test/java/com/example/demo/user/repository/UserPiiEncryptionRepositoryTest.java
git commit -m "feat: User.email/name에 AES-256-GCM 컨버터 적용, emailHash 조회 컬럼 추가"
```

---

### Task 4: UserService — 해시 기반 조회로 전환

**Files:**
- Modify: `src/main/java/com/example/demo/user/service/UserService.java:47-101` (signup, findIdSendCode, findIdVerifyCode)
- Modify: `src/test/java/com/example/demo/user/service/UserServiceTest.java`

**Interfaces:**
- Consumes: `EmailHasher.hash`(Task 2), `UserRepository.findByEmailHash`/`existsByEmailHash`(Task 3)
- Produces: 없음(외부에 노출되는 시그니처 변경 없음 — `UserService.signup`/`findIdSendCode`/`findIdVerifyCode`의 파라미터·리턴 타입은 그대로)

- [ ] **Step 1: 테스트를 새 인터페이스에 맞게 먼저 고친다**

`src/test/java/com/example/demo/user/service/UserServiceTest.java` 최상단 import에 추가:

```java
import com.example.demo.global.util.EmailHasher;
```

기존 `existsByEmail(request.getEmail())` 3곳을 `existsByEmailHash(EmailHasher.hash(request.getEmail()))`로 교체:

```java
        when(userRepository.existsByEmailHash(EmailHasher.hash(request.getEmail()))).thenReturn(true);
```
(85번째 줄 `이미_가입된_이메일이면_예외가_발생한다` 테스트)

```java
        when(userRepository.existsByEmailHash(EmailHasher.hash(request.getEmail()))).thenReturn(false);
```
(97번째 줄 `정상_요청이면...` 테스트, 119번째 줄 `마케팅_동의하고...` 테스트에 각각 동일하게 적용)

`정상_요청이면_비밀번호를_인코딩해서_저장하고_인증플래그를_지운다` 테스트에 emailHash 검증 한 줄 추가:

```java
        assertThat(saved.getPassword()).isEqualTo("ENCODED");
        assertThat(saved.getEmailHash()).isEqualTo(EmailHasher.hash(request.getEmail()));
        assertThat(saved.getProvider()).isEqualTo(Provider.LOCAL);
```

`findByEmail("...")` 4곳을 `findByEmailHash(EmailHasher.hash("..."))`로 교체(이메일 문자열은 그대로 유지, 감싸는 메서드만 바뀜):

```java
        when(userRepository.findByEmailHash(EmailHasher.hash("tester01@example.com"))).thenReturn(Optional.of(user));
```
(153번째 줄), 

```java
        when(userRepository.findByEmailHash(EmailHasher.hash("nouser@example.com"))).thenReturn(Optional.empty());
```
(162번째 줄),

```java
        when(userRepository.findByEmailHash(EmailHasher.hash("google-user@example.com"))).thenReturn(Optional.of(googleUser));
```
(180번째 줄과 198번째 줄, 두 테스트에 각각),

```java
        when(userRepository.findByEmailHash(EmailHasher.hash("tester01@example.com"))).thenReturn(Optional.of(user));
```
(214번째 줄)

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.UserServiceTest"`
Expected: FAIL — `UserService`가 여전히 `existsByEmail`/`findByEmail`을 호출해서 목이 매칭 안 되고(`Optional.empty()` 기본값), 관련 테스트들이 깨짐

- [ ] **Step 3: 프로덕션 코드 수정**

`src/main/java/com/example/demo/user/service/UserService.java` 상단 import에 추가:

```java
import com.example.demo.global.util.EmailHasher;
```

`signup` 메서드 수정(56번째 줄 근방):

```java
        if (userRepository.existsByEmailHash(EmailHasher.hash(request.getEmail()))) {
            throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
        }

        User user = User.builder()
                .loginId(request.getLoginId())
                .email(request.getEmail())
                .emailHash(EmailHasher.hash(request.getEmail()))
                .password(passwordEncoder.encode(request.getPassword()))
                .name(request.getName())
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build();
```

`findIdSendCode`/`findIdVerifyCode` 수정(76번째, 87번째 줄):

```java
    public void findIdSendCode(String email) {
        User user = userRepository.findByEmailHash(EmailHasher.hash(email)).orElse(null);
        if (user == null || user.getLoginId() == null) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }

        emailVerificationService.sendCode(email, "아이디 찾기");
    }

    public FindIdResponse findIdVerifyCode(String email, String code) {
        emailVerificationService.verifyCodeOnly(email, code);

        User user = userRepository.findByEmailHash(EmailHasher.hash(email))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
```

(`passwordSendCode`/`passwordVerifyCode`는 `findByLoginId` 후 `user.getEmail().equals(email)`로 메모리상에서 비교하므로 변경 불필요 — 컨버터가 이미 복호화된 값을 돌려준다.)

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.UserServiceTest"`
Expected: PASS (전체)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/demo/user/service/UserService.java src/test/java/com/example/demo/user/service/UserServiceTest.java
git commit -m "refactor: UserService가 이메일 조회를 emailHash 기반으로 수행하도록 변경"
```

---

### Task 5: AuthService — 구글 로그인 조회를 해시 기반으로 전환

**Files:**
- Modify: `src/main/java/com/example/demo/user/service/AuthService.java:74-97` (googleLogin)
- Modify: `src/test/java/com/example/demo/user/service/AuthServiceTest.java`

**Interfaces:**
- Consumes: `EmailHasher.hash`(Task 2), `UserRepository.findByEmailHash`(Task 3)
- Produces: 없음(외부 시그니처 변경 없음)

- [ ] **Step 1: 테스트를 새 인터페이스에 맞게 먼저 고친다**

`src/test/java/com/example/demo/user/service/AuthServiceTest.java` 최상단 import에 추가:

```java
import com.example.demo.global.util.EmailHasher;
```

`findByEmail("...")` 4곳을 `findByEmailHash(EmailHasher.hash("..."))`로 교체:

```java
        when(userRepository.findByEmailHash(EmailHasher.hash("newgoogle@example.com"))).thenReturn(Optional.empty());
```
(248번째 줄, `신규_구글_사용자면...` 테스트)

```java
        when(userRepository.findByEmailHash(EmailHasher.hash("existing@example.com"))).thenReturn(Optional.of(existing));
```
(295번째 줄, `기존_구글_사용자면...` 테스트)

```java
        when(userRepository.findByEmailHash(EmailHasher.hash("racer@example.com"))).thenReturn(Optional.empty());
```
(316번째 줄, `동시_회원가입_경쟁에서...` 테스트)

```java
        when(userRepository.findByEmailHash(EmailHasher.hash("local@example.com"))).thenReturn(Optional.of(localUser));
```
(347번째 줄, `이미_로컬_계정으로...` 테스트)

`신규_구글_사용자면_자동으로_회원가입하고_토큰을_발급한다` 테스트에 emailHash 검증 한 줄 추가:

```java
        assertThat(createdUser.getEmail()).isEqualTo("newgoogle@example.com");
        assertThat(createdUser.getEmailHash()).isEqualTo(EmailHasher.hash("newgoogle@example.com"));
        assertThat(createdUser.getName()).isEqualTo("구글사용자");
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.AuthServiceTest"`
Expected: FAIL — `AuthService`가 여전히 `findByEmail`을 호출해서 목이 매칭 안 됨

- [ ] **Step 3: 프로덕션 코드 수정**

`src/main/java/com/example/demo/user/service/AuthService.java` 상단 import에 추가:

```java
import com.example.demo.global.util.EmailHasher;
```

`googleLogin` 메서드 수정(74-89번째 줄 근방):

```java
    @Transactional
    public TokenResponse googleLogin(String code, String redirectUri) {
        GoogleUserInfo googleUserInfo = googleOAuthClient.fetchUserInfo(code, redirectUri);

        User user = userRepository.findByEmailHash(EmailHasher.hash(googleUserInfo.getEmail())).orElse(null);

        if (user == null) {
            User newUser = User.builder()
                    .email(googleUserInfo.getEmail())
                    .emailHash(EmailHasher.hash(googleUserInfo.getEmail()))
                    .name(googleUserInfo.getName())
                    .provider(Provider.GOOGLE)
                    .providerId(googleUserInfo.getProviderId())
                    .role(Role.USER)
                    .build();
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.AuthServiceTest"`
Expected: PASS (전체)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/demo/user/service/AuthService.java src/test/java/com/example/demo/user/service/AuthServiceTest.java
git commit -m "refactor: AuthService의 구글 로그인 조회를 emailHash 기반으로 변경"
```

---

### Task 6: 기존 평문 행 백필 — PiiMigrationService + 기동 시 자동 실행

**Files:**
- Create: `src/main/java/com/example/demo/user/service/PiiMigrationService.java`
- Create: `src/main/java/com/example/demo/user/config/PiiMigrationRunner.java`
- Test: `src/test/java/com/example/demo/user/service/PiiMigrationServiceTest.java`

**Interfaces:**
- Consumes: `UserRepository.findByEmailHashIsNull`/`saveAll`(Task 3), `EmailHasher.hash`(Task 2)
- Produces: `PiiMigrationService.migratePendingUsers()`(앱 기동 시 `PiiMigrationRunner`가 호출)

**동작 원리:** `emailHash`가 `null`인 행(=아직 마이그레이션 안 된 레거시 행)을 찾아 `emailHash`만 채우고 `saveAll`한다. `User` 엔티티는 `@DynamicUpdate`를 쓰지 않으므로 Hibernate는 기본적으로 매핑된 전체 컬럼을 `UPDATE`에 포함시킨다 — 즉 `email`/`name` 필드 값 자체는 안 건드려도, 저장 시점에 `PiiCryptoConverter.convertToDatabaseColumn`이 다시 호출되면서 (읽을 때 `PiiCipher.decrypt`의 레거시-평문 폴백으로 얻은) 평문이 이번엔 정상적으로 암호화되어 다시 쓰인다. 다음 로드부터는 `ENC:` 접두사가 붙어 있으니 정상 복호화 경로를 탄다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/demo/user/service/PiiMigrationServiceTest.java`:

```java
package com.example.demo.user.service;

import com.example.demo.global.util.EmailHasher;
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PiiMigrationServiceTest {

    @Mock
    private UserRepository userRepository;

    private PiiMigrationService piiMigrationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        piiMigrationService = new PiiMigrationService(userRepository);
    }

    @Test
    void emailHash가_없는_기존_행에_해시를_채우고_저장한다() {
        User legacyUser = User.builder()
                .id(1L)
                .email("legacy@example.com")
                .name("레거시유저")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build();
        when(userRepository.findByEmailHashIsNull()).thenReturn(List.of(legacyUser));

        piiMigrationService.migratePendingUsers();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<User>> captor = ArgumentCaptor.forClass(List.class);
        verify(userRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getEmailHash()).isEqualTo(EmailHasher.hash("legacy@example.com"));
    }

    @Test
    void 마이그레이션_대상이_없으면_저장을_호출하지_않는다() {
        when(userRepository.findByEmailHashIsNull()).thenReturn(List.of());

        piiMigrationService.migratePendingUsers();

        verify(userRepository, never()).saveAll(any());
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.PiiMigrationServiceTest"`
Expected: FAIL — `PiiMigrationService` 클래스가 없어서 컴파일 에러

- [ ] **Step 3: 최소 구현 작성**

`src/main/java/com/example/demo/user/service/PiiMigrationService.java`:

```java
package com.example.demo.user.service;

import com.example.demo.global.util.EmailHasher;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PiiMigrationService {

    private final UserRepository userRepository;

    @Transactional
    public void migratePendingUsers() {
        List<User> pending = userRepository.findByEmailHashIsNull();
        if (pending.isEmpty()) {
            return;
        }

        for (User user : pending) {
            user.setEmailHash(EmailHasher.hash(user.getEmail()));
        }

        userRepository.saveAll(pending);
    }
}
```

`src/main/java/com/example/demo/user/config/PiiMigrationRunner.java`:

```java
package com.example.demo.user.config;

import com.example.demo.user.service.PiiMigrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 기동 시 emailHash가 비어있는(=암호화 적용 전에 만들어진) 기존 행을 찾아
 * 백필한다. 다른 ApplicationRunner(데모 데이터 초기화 등)보다 먼저 돌도록
 * 순서를 낮게 잡는다.
 */
@Component
@RequiredArgsConstructor
@Order(0)
public class PiiMigrationRunner implements ApplicationRunner {

    private final PiiMigrationService piiMigrationService;

    @Override
    public void run(ApplicationArguments args) {
        piiMigrationService.migratePendingUsers();
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.PiiMigrationServiceTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/demo/user/service/PiiMigrationService.java src/main/java/com/example/demo/user/config/PiiMigrationRunner.java src/test/java/com/example/demo/user/service/PiiMigrationServiceTest.java
git commit -m "feat: 기동 시 기존 평문 email/name 행을 자동 백필하는 마이그레이션 러너 추가"
```

---

### Task 7: 전체 회귀 테스트 + PR

**Files:** 없음(검증 + PR만)

- [ ] **Step 1: 전체 테스트 스위트 실행**

Run: `./gradlew test`
Expected: PASS — 전체 테스트 그린(회원가입/로그인/구글로그인/아이디찾기/비밀번호찾기/게시판/댓글 등 기존 테스트 전부 포함, 회귀 없어야 함)

- [ ] **Step 2: 빌드 확인**

Run: `./gradlew build`
Expected: PASS

- [ ] **Step 3: 원격에 푸시하고 PR 생성**

```bash
git push -u origin feature/pii-column-encryption-v2
gh pr create --title "feat: User email/name AES-256-GCM 컬럼 암호화 (재시도)" --body "$(cat <<'EOF'
## Summary
- User.email/name을 JPA AttributeConverter(PiiCryptoConverter)로 AES-256-GCM 암호화해서 저장
- 검색용 emailHash(HMAC-SHA256) 컬럼 추가, unique 제약을 email → emailHash로 이동
- 기존 findByEmail/existsByEmail 호출부(UserService 2곳, AuthService 1곳)를 해시 기반 조회로 교체
- 이메일은 소문자 정규화 후 해시 — 기존 대소문자 무관 조회 동작 유지

## 지난 시도(PR #52/#53)와 달라진 점
지난 시도는 마이그레이션 없이 배포했고, 복호화 실패 시 예외를 던지는 구조라 레거시
평문 행을 만나면 게시글 조회/작성이 전부 500으로 죽었다(당일 revert). 이번엔:
- PiiCipher.decrypt()가 ENC: 접두사가 없거나 복호화 실패 시 **원문 그대로 반환**(예외 없음)
- PiiMigrationRunner가 기동 시 emailHash가 비어있는 기존 행을 자동으로 찾아 암호화+해시 백필
- 위 두 장치가 겹쳐 있어서, 마이그레이션이 놓친 행이 있어도 장애로 이어지지 않는다

## Test plan
- [x] PiiCipherTest: 암/복호화 왕복, 매번 다른 암호문, 레거시 평문/손상값 폴백
- [x] EmailHasherTest: 해시 일관성, 대소문자/공백 무관
- [x] UserPiiEncryptionRepositoryTest: 실제 H2 DB round-trip, 해시 조회
- [x] UserServiceTest / AuthServiceTest: 해시 기반 조회로 갱신 후 전부 통과
- [x] PiiMigrationServiceTest: 백필 로직
- [x] `./gradlew test` 전체 그린

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```
