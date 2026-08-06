# 개인정보 컬럼 암호화 (email, name) 설계

## 배경 / 목적
현재 `users` 테이블의 `email`, `name`은 평문으로 저장된다. 비밀번호만 BCrypt로 해시되어 있고, 나머지 개인정보는 DB 유출/백업 노출/내부자 접근 시 그대로 노출된다. `email`/`name`을 컬럼 단위로 암호화해 저장 시점 노출 리스크를 줄인다.

## 범위
- 대상 컬럼: `User.email`, `User.name`
- 기존 데이터 마이그레이션: **불필요**. 기존 DB 데이터는 테스트용이라 그대로 두거나 재시딩하면 되고, 별도 마이그레이션 스크립트를 만들지 않는다.
- 배포 이후 신규로 저장되는 값부터 자동으로 암호화된다.

## 조사 결과 (기존 사용처)
- `email`은 `findByEmail`/`existsByEmail`로 **정확 일치 조회**에만 쓰인다 (로그인, 아이디/비번 찾기, 구글 로그인 자동가입, 관리자 이메일 알림 등). LIKE/부분검색 없음.
- `name`은 어디서도 조회 조건(WHERE)으로 쓰이지 않는다. 항상 이미 로드된 엔티티에서 `getName()`으로 화면 표시/게시글 작성자 표시용으로만 쓰인다.
- `AdminUserService.getAllUsers()`는 `findAll()` 후 스트림 처리라 컬럼 암호화와 무관하게 동작한다(전체 로드 후 애플리케이션에서 필터링).

이 차이 때문에 `email`과 `name`의 처리 방식이 갈린다.

## 아키텍처

### 공통: JPA `AttributeConverter`
필드 레벨에서 암/복호화를 투명하게 처리한다. 애플리케이션 코드(`user.getEmail()`, `user.getName()`, `UserService`, `AuthService` 등)는 지금처럼 평문 문자열을 다루고, DB에 쓰고 읽는 시점에만 자동 변환된다. 서비스/컨트롤러 코드는 수정하지 않는다.

- 암호화 알고리즘: AES-256-GCM (IV는 매 암호화마다 랜덤 생성, 암호문 앞에 붙여 저장)
- 키: 환경변수 `PII_ENCRYPTION_KEY`로 관리, 기존 `JWT_SECRET`과 동일한 이름 규칙

**구현 중 변경된 부분**: 처음엔 `JwtProvider`처럼 `@Value("${pii.encryption-key}")`로 Spring이 주입하게 설계했지만, `@DataJpaTest` 슬라이스 컨텍스트는 일반 `@Component`(`PiiCryptoService`)를 스캔하지 않아서 Hibernate가 컨버터를 초기화하는 시점에 `BeanCreationException`이 났다(`BoardRepositoryTest`, `CommentRepositoryTest`, `UserConsentRepositoryTest`, `WithdrawalServiceIntegrationTest`에서 재현). JPA 컨버터는 Hibernate가 Spring 컨텍스트 범위와 무관하게 직접 인스턴스화하므로, Spring DI에 의존하지 않는 편이 근본적으로 안전하다.

그래서 실제 구조는 3단으로 나뉜다:
- `PiiCipher` (package-private, static): 실제 AES-256-GCM/HMAC-SHA256 로직. 키는 `System.getProperty("PII_ENCRYPTION_KEY")` → `System.getenv("PII_ENCRYPTION_KEY")` → dev 기본값 순으로 직접 읽는다 (`HashUtil`과 동일한 무-DI 스타일).
- `PiiCryptoConverter` (`@Converter`, Spring 빈 아님): Hibernate가 어떤 컨텍스트에서도 `new`로 만들 수 있도록 `PiiCipher`를 직접 호출.
- `PiiCryptoService` (`@Component`): `PiiCipher`를 감싸는 얇은 래퍼. `UserService`/`AuthService`에 주입해서 유닛테스트에서 모킹하기 위한 용도.

`application.yaml`에는 별도 프로퍼티를 두지 않는다(어차피 안 읽으므로). `DemoApplication`의 dotenv 로딩에 `PII_ENCRYPTION_KEY`를 추가해 로컬 개발 시 `.env`로 지정할 수 있게 한다.

### `name`: 단순 암호화
- `NameCryptoConverter implements AttributeConverter<String, String>` 하나만 붙인다.
- 조회 조건으로 안 쓰이므로 이걸로 끝.

### `email`: 암호화 + 검색용 해시 컬럼
- `email` 컬럼: `EmailCryptoConverter`로 AES-256-GCM 암호화 저장 (표시/실제 발송용 값)
- `email_hash` 컬럼 신규 추가: HMAC-SHA256(email, 별도 키 또는 동일 키의 다른 용도) — 같은 입력이면 항상 같은 해시값 → 검색 가능
- **unique 제약을 `email` → `email_hash`로 이동**: AES-GCM은 같은 평문도 매번 다른 암호문을 내므로, 지금처럼 `email` 컬럼에 unique를 걸면 중복 이메일 가입이 통과해버린다.
- `UserRepository`:
  ```java
  Optional<User> findByEmailHash(String emailHash);
  boolean existsByEmailHash(String emailHash);
  ```
  기존 `findByEmail`/`existsByEmail` 호출부는 "이메일 문자열로 해시를 계산해서 위 메서드를 호출"하는 얇은 헬퍼(`EmailHasher` 유틸 또는 서비스 메서드)를 거치도록 바꾼다. `User.email` 필드 자체는 컨버터가 자동 처리하므로 엔티티 코드는 그대로 둔다.

### 엔티티 변경 (`User.java`)
```java
@Convert(converter = PiiCryptoConverter.class)
@Column(nullable = false, length = 255)   // 암호문이 평문보다 길어지므로 length 상향
private String email;

@Column(unique = true, length = 64)   // nullable — 기존 테스트 데이터/DataJpaTest 픽스처는 emailHash 없이도 저장 가능해야 함
private String emailHash;

@Convert(converter = PiiCryptoConverter.class)
@Column(nullable = false, length = 255)
private String name;
```
`email`/`name` 컬럼 length는 암호문 길이(Base64 인코딩된 IV+ciphertext)를 감안해 넉넉히 늘린다. `EmailCryptoConverter`/`NameCryptoConverter`로 나누려 했으나 로직이 완전히 동일해서 `PiiCryptoConverter` 하나로 통합했다(둘 다 이 컨버터를 붙임).

`emailHash`는 `nullable = false`가 아니라 nullable로 뒀다 — `WithdrawalServiceIntegrationTest`, `UserConsentRepositoryTest` 등 기존 `@DataJpaTest` 픽스처가 `emailHash` 없이 `User.builder()`로 직접 저장하고 있어서, NOT NULL로 걸면 그 테스트들을 전부 고쳐야 했다. unique 컬럼에서 NULL은 여러 개 허용되므로(H2/MySQL 공통) 제약 위반 없이 공존 가능하다.

## 데이터 흐름
- **회원가입**: `UserService.signup()` → `User` 빌드 시 `emailHash = EmailHasher.hash(email)`도 함께 설정 → JPA 저장 시 컨버터가 `email`/`name`을 암호화. 중복 체크는 `existsByEmailHash(EmailHasher.hash(email))`로 수행.
- **로그인/조회**: 입력 이메일을 해시로 변환 후 `findByEmailHash`로 조회 → 로드된 엔티티는 컨버터가 자동 복호화하므로 `user.getEmail()`은 평문을 돌려준다.
- **이메일 발송**(인증코드, 관리자 알림 등): `user.getEmail()`이 항상 평문을 반환하므로 기존 로직 변경 없음.

## 에러 처리
- 앱 기동 시 `PII_ENCRYPTION_KEY`가 dev 더미값이면 로그로 경고만 남김 (기존 `JWT_SECRET` 처리 방식과 동일 — 별도 fail-fast 로직 추가하지 않음, YAGNI).
- 컨버터에서 복호화 실패(키 불일치, 손상된 데이터) 시 `IllegalStateException`으로 즉시 실패 — 조용히 null 반환하거나 원문을 흘리지 않는다.

## 테스트
- `EmailCryptoConverter`/`NameCryptoConverter` 왕복 테스트 (암호화 후 복호화하면 원문과 동일한지)
- `EmailHasher` 같은 입력 → 같은 해시, 다른 입력 → 다른 해시 확인 (기존 `HashUtilTest` 패턴과 동일)
- `UserServiceTest`의 회원가입 중복 체크 테스트를 `existsByEmailHash` 기준으로 갱신

## 마이그레이션 (미실시)
기존 데이터는 테스트용이라 마이그레이션하지 않는다. 컬럼 타입/제약(`email` length 확장, `email_hash` 추가 + unique)은 `ddl-auto: update`로 자동 반영되지만, 기존 행의 `email`은 평문 그대로 남고 `email_hash`는 null로 남는다 — 이 상태에서는 기존 계정으로 로그인 시 `findByEmailHash` 조회가 실패한다는 한계가 있음을 인지하고 진행한다 (테스트 데이터이므로 허용).
