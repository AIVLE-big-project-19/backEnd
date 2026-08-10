# 관리자 회원목록 이름/이메일 마스킹 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관리자 회원목록(`GET /admin/users`) 응답에서 회원 이름/이메일을 서버 단에서 항상 마스킹된 형태로 내려주도록 만든다.

**Architecture:** 순수 함수 유틸 클래스(`MaskingUtil`)를 `global/util` 패키지에 추가하고, `AdminUserResponse.from(User)`가 이름/이메일을 대입할 때 그 유틸을 거치도록 한 줄씩 바꾼다. 프론트/DB/다른 API는 건드리지 않는다.

**Tech Stack:** Spring Boot(Java), JUnit 5 + AssertJ (기존 `HashUtilTest`와 동일한 컨벤션)

## Global Constraints

- 이름 마스킹: null/빈 문자열은 그대로 반환, 1글자는 그대로 반환, 2글자 이상은 첫 글자 + `*` × (길이-1)
- 이메일 마스킹: `@`가 없으면 그대로 반환, 로컬파트 길이 ≥ 3이면 앞 3자 + `*` × (로컬파트 길이-3), 1~2자면 앞 1자 + `*` × (로컬파트 길이-1), 도메인은 항상 그대로 유지
- 마스킹은 표시용이며 DB 저장값(User 엔티티)은 절대 변경하지 않는다
- `AdminUserResponse.from()`을 호출하는 모든 곳(`AdminUserService.getUsers()`, `AdminUserService.changeRole()`)에 자동으로 적용되어야 한다 — 별도 분기 없이 `from()` 내부 한 곳만 고치면 됨

---

## Task 1: MaskingUtil.maskName 구현

**Files:**
- Create: `src/main/java/com/example/demo/global/util/MaskingUtil.java`
- Test: `src/test/java/com/example/demo/global/util/MaskingUtilTest.java`

**Interfaces:**
- Consumes: 없음 (순수 함수, 신규 파일)
- Produces: `MaskingUtil.maskName(String name)` — `public static String`, Task 3에서 `AdminUserResponse`가 이 시그니처로 호출함

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/demo/global/util/MaskingUtilTest.java` 파일을 새로 만들고 다음을 작성한다:

```java
package com.example.demo.global.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MaskingUtilTest {

    @Test
    void 이름이_null이면_그대로_반환한다() {
        assertThat(MaskingUtil.maskName(null)).isNull();
    }

    @Test
    void 이름이_빈문자열이면_그대로_반환한다() {
        assertThat(MaskingUtil.maskName("")).isEqualTo("");
    }

    @Test
    void 이름이_한글자면_그대로_반환한다() {
        assertThat(MaskingUtil.maskName("이")).isEqualTo("이");
    }

    @Test
    void 이름이_두글자면_첫글자만_남기고_가린다() {
        assertThat(MaskingUtil.maskName("이도")).isEqualTo("이*");
    }

    @Test
    void 이름이_세글자면_첫글자만_남기고_나머지를_가린다() {
        assertThat(MaskingUtil.maskName("한승연")).isEqualTo("한**");
    }

    @Test
    void 이름에_공백이_포함되어도_길이만큼_가린다() {
        assertThat(MaskingUtil.maskName("z z")).isEqualTo("z**");
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.global.util.MaskingUtilTest"`
Expected: FAIL — `MaskingUtil` 클래스가 없어서 컴파일 에러

- [ ] **Step 3: 최소 구현 작성**

`src/main/java/com/example/demo/global/util/MaskingUtil.java` 파일을 새로 만든다:

```java
package com.example.demo.global.util;

public final class MaskingUtil {

    private MaskingUtil() {
    }

    public static String maskName(String name) {
        if (name == null || name.length() <= 1) {
            return name;
        }
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.global.util.MaskingUtilTest"`
Expected: PASS (6개 테스트 전부)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/demo/global/util/MaskingUtil.java src/test/java/com/example/demo/global/util/MaskingUtilTest.java
git commit -m "feat: 이름 마스킹 유틸(MaskingUtil.maskName) 추가"
```

---

## Task 2: MaskingUtil.maskEmail 구현

**Files:**
- Modify: `src/main/java/com/example/demo/global/util/MaskingUtil.java`
- Modify: `src/test/java/com/example/demo/global/util/MaskingUtilTest.java`

**Interfaces:**
- Consumes: 없음 (Task 1의 `MaskingUtil` 클래스에 메서드 추가)
- Produces: `MaskingUtil.maskEmail(String email)` — `public static String`, Task 3에서 `AdminUserResponse`가 이 시그니처로 호출함

- [ ] **Step 1: 실패하는 테스트 작성**

`MaskingUtilTest.java`에 아래 테스트들을 추가한다 (기존 6개 테스트는 그대로 둔다):

```java
    @Test
    void 이메일이_null이면_그대로_반환한다() {
        assertThat(MaskingUtil.maskEmail(null)).isNull();
    }

    @Test
    void 골뱅이가_없으면_그대로_반환한다() {
        assertThat(MaskingUtil.maskEmail("not-an-email")).isEqualTo("not-an-email");
    }

    @Test
    void 로컬파트가_한글자면_그대로_노출하고_나머지는_없다() {
        assertThat(MaskingUtil.maskEmail("a@gmail.com")).isEqualTo("a@gmail.com");
    }

    @Test
    void 로컬파트가_두글자면_앞한글자만_남기고_가린다() {
        assertThat(MaskingUtil.maskEmail("ab@gmail.com")).isEqualTo("a*@gmail.com");
    }

    @Test
    void 로컬파트가_세글자면_전부_노출된다() {
        assertThat(MaskingUtil.maskEmail("abc@gmail.com")).isEqualTo("abc@gmail.com");
    }

    @Test
    void 로컬파트가_세글자보다_길면_앞세글자만_남기고_가린다() {
        assertThat(MaskingUtil.maskEmail("s2ungyeon.h@gmail.com")).isEqualTo("s2u********@gmail.com");
    }

    @Test
    void 도메인은_항상_그대로_유지된다() {
        assertThat(MaskingUtil.maskEmail("htmddus49@gmail.com")).isEqualTo("htm******@gmail.com");
    }
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.global.util.MaskingUtilTest"`
Expected: FAIL — `maskEmail` 메서드가 없어서 컴파일 에러

- [ ] **Step 3: 최소 구현 작성**

`MaskingUtil.java`에 아래 메서드를 추가한다 (`maskName` 아래에):

```java
    public static String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        int atIndex = email.indexOf('@');
        if (atIndex < 0) {
            return email;
        }

        String localPart = email.substring(0, atIndex);
        String domain = email.substring(atIndex);

        int visibleLength = localPart.length() >= 3 ? 3 : Math.min(1, localPart.length());
        String visible = localPart.substring(0, visibleLength);
        String masked = "*".repeat(localPart.length() - visibleLength);

        return visible + masked + domain;
    }
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.global.util.MaskingUtilTest"`
Expected: PASS (13개 테스트 전부)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/demo/global/util/MaskingUtil.java src/test/java/com/example/demo/global/util/MaskingUtilTest.java
git commit -m "feat: 이메일 마스킹 유틸(MaskingUtil.maskEmail) 추가"
```

---

## Task 3: AdminUserResponse에 마스킹 적용

**Files:**
- Modify: `src/main/java/com/example/demo/user/dto/AdminUserResponse.java:22-32`
- Test: `src/test/java/com/example/demo/user/dto/AdminUserResponseTest.java` (신규)

**Interfaces:**
- Consumes: `MaskingUtil.maskName(String)`, `MaskingUtil.maskEmail(String)` (Task 1, 2에서 완성됨)
- Produces: `AdminUserResponse.from(User)`가 마스킹된 `name`/`email`을 담은 `AdminUserResponse`를 반환 — `AdminUserService.getUsers()`와 `AdminUserService.changeRole()`이 이미 이 메서드를 호출하고 있으므로 두 곳 다 자동으로 마스킹 적용됨 (별도 수정 불필요)

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/demo/user/dto/AdminUserResponseTest.java` 파일을 새로 만든다. `User` 엔티티는 빌더로 생성 가능한지 먼저 확인하고(`user/entity/User.java`의 `@Builder` 여부), 아래처럼 작성한다:

```java
package com.example.demo.user.dto;

import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminUserResponseTest {

    @Test
    void from은_이름과_이메일을_마스킹해서_담는다() {
        User user = User.builder()
                .id(1L)
                .loginId("hansy")
                .email("s2ungyeon.h@gmail.com")
                .name("한승연")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build();

        AdminUserResponse response = AdminUserResponse.from(user);

        assertThat(response.getName()).isEqualTo("한**");
        assertThat(response.getEmail()).isEqualTo("s2u********@gmail.com");
        assertThat(response.getLoginId()).isEqualTo("hansy");
    }
}
```

만약 `User`에 `@Builder`가 없다면, 기존 `AdminUserService` 관련 테스트나 `User` 엔티티 파일을 열어 실제 생성 방식(생성자 등)을 확인해서 그 방식으로 테스트의 `User` 인스턴스를 만든다.

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.dto.AdminUserResponseTest"`
Expected: FAIL — `getName()`이 `"한승연"`을 반환해서 `"한**"`과 다름 (assertion 실패)

- [ ] **Step 3: 최소 구현 작성**

`src/main/java/com/example/demo/user/dto/AdminUserResponse.java`를 연다. 상단 import에 아래를 추가:

```java
import com.example.demo.global.util.MaskingUtil;
```

`from()` 메서드(22~32줄)를 아래로 교체:

```java
    public static AdminUserResponse from(User user) {
        return AdminUserResponse.builder()
                .id(user.getId())
                .loginId(user.getLoginId())
                .email(MaskingUtil.maskEmail(user.getEmail()))
                .name(MaskingUtil.maskName(user.getName()))
                .provider(user.getProvider())
                .role(user.getRole())
                .createdAt(user.getCreatedAt())
                .build();
    }
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.dto.AdminUserResponseTest"`
Expected: PASS

- [ ] **Step 5: 전체 테스트 스위트 실행해서 회귀 없는지 확인**

Run: `./gradlew test`
Expected: 전부 PASS (기존 `AdminUserService`/`AdminUserController` 관련 테스트가 없으므로 이번 변경으로 깨질 기존 테스트는 없음)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/example/demo/user/dto/AdminUserResponse.java src/test/java/com/example/demo/user/dto/AdminUserResponseTest.java
git commit -m "feat: 관리자 회원목록 응답에 이름/이메일 마스킹 적용"
```

---

## Task 4: 로컬에서 실제 화면 확인

**Files:** 없음 (코드 변경 없음, 수동 확인만)

**Interfaces:**
- Consumes: Task 1~3에서 완성된 마스킹 로직이 반영된 `/admin/users` 응답
- Produces: 없음 (검증 전용 태스크)

- [ ] **Step 1: 로컬 백엔드 실행**

Run: `./gradlew bootRun`
Expected: `Started DemoApplication` 로그와 함께 정상 구동 (로컬 MySQL이 켜져있어야 함 — `application.yaml` 기본값은 `localhost:3306/solaraivle`)

- [ ] **Step 2: 관리자 계정으로 로그인해서 회원 목록 API 직접 호출**

로컬에 `admin` 계정이 없다면 먼저 회원가입 후 DB에서 role을 `ADMIN`으로 수동 변경한다 (기존에 해봤던 절차와 동일).

로그인 후 발급받은 accessToken으로:

```bash
curl -H "Authorization: Bearer <accessToken>" http://localhost:8080/api/admin/users
```

Expected: 응답 JSON의 각 회원 `name`이 `"한**"` 같은 형태, `email`이 `"s2u********@gmail.com"` 같은 형태로 마스킹되어 내려옴. `loginId`는 마스킹 없이 원본 그대로.

- [ ] **Step 3: 프론트에서도 확인 (선택)**

프론트 로컬 개발 서버를 켜고(`npm run dev`) `/admin/users` 화면에 접속해, 아까 스크린샷에서 문제였던 이름/이메일 컬럼이 마스킹된 형태로 보이는지 확인한다.

---

## Self-Review 결과

- **스펙 커버리지**: 설계 문서의 핵심 결정사항 6개 모두 Task 1~3에 반영됨 (적용 위치=백엔드/Task 3, 대상 필드=이름+이메일만/Task 1·2, 원본보기 없음=별도 태스크 없음, 이름 규칙=Task 1, 이메일 규칙=Task 2, 순수 함수 유틸=Task 1·2). `loginId`는 건드리지 않는다는 요구사항은 Task 3 테스트의 `getLoginId()` 검증으로 확인.
- **플레이스홀더 스캔**: 없음 — 모든 스텝에 실제 코드/커맨드 포함.
- **타입 일관성**: `maskName(String)`, `maskEmail(String)` 시그니처가 Task 1·2 구현과 Task 3 사용처에서 동일하게 유지됨.
