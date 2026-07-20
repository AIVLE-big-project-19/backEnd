# 회원탈퇴 (Phase 6) 설계 문서

## 배경

- `PRIVACY.md`(개인정보처리방침)에 "회원 탈퇴 시까지 보유하며, 탈퇴 시 지체 없이 파기합니다"라고 공표했으나 탈퇴 기능 자체가 없어, 처리방침이 이행 불가능한 약속을 하고 있는 상태다.
- Phase 4(개인정보 동의) 설계에서 "필수 동의 철회 = 회원탈퇴 영역"으로 명시적으로 미뤄둔 항목이다.
- 정보주체의 삭제 요구권(개인정보 보호법) 이행 수단이기도 하다.

## 범위

**포함:**
- 탈퇴 API: 본인확인(LOCAL만) → 게시글/댓글 익명화 → 토큰·동의이력·유저 행 완전 삭제 → Redis 정리
- 문서 갱신 (`API_REFERENCE.md`)

**제외 (후속 과제):**
- 탈퇴 유예기간/복구 (즉시 완전 삭제 원칙 채택)
- 재가입 제한 (탈퇴 직후 같은 이메일로 재가입 가능 — 식별 정보를 남기지 않으므로 기술적으로 제한 불가, 의도된 결과)
- 관리자에 의한 강제 탈퇴
- 탈퇴 사유 수집

## 핵심 결정사항

| 결정 | 선택 | 근거 |
|---|---|---|
| 삭제 방식 | 완전 삭제 (hard delete) | PRIVACY.md "지체 없이 파기" 문구와 정확히 일치. 구현 단순 |
| 게시글/댓글 | 삭제하지 않고 익명화 ("탈퇴한 사용자") | 커뮤니티 맥락 보존. 글 내용은 개인정보가 아님. 통상적 커뮤니티 관례 |
| 본인확인 | LOCAL: 비밀번호 재입력 / GOOGLE: 생략 | 구글 계정은 비밀번호가 없고 이미 구글 인증을 거친 세션. 프론트 확인 모달로 충분 |
| 로직 위치 | 신규 `WithdrawalService` | 6개 저장소/서비스를 한 트랜잭션으로 조율하는 유일한 기능. `ConsentService`/`LoginAttemptService`와 같은 독립 서비스 관례 |
| API | `POST /users/me/withdrawal` | `DELETE /users/me` + body는 일부 프록시가 body를 버리는 리스크. 비밀번호를 body로 받아야 하므로 POST |
| 동의 이력 | 유저와 함께 삭제 | 동의 이력도 개인정보. hard delete 원칙 및 처리방침 문구와 일치. FK 제약상 유저 행 이전에 삭제 |

## API 명세

### `POST /users/me/withdrawal` (인증 필요, MyPageController에 추가)

- `@AuthenticationPrincipal Long userId` (기존 마이페이지 패턴)
- 요청: `{ "password": "현재비밀번호" }` — LOCAL 계정은 필수, GOOGLE 계정은 무시(미전송 허용). DTO 필드는 optional (`@NotBlank` 불가 — 구글 때문), 검증은 서비스에서.
- 성공: 200, `SuccessCode.USER_WITHDRAWN("회원 탈퇴가 완료되었습니다.")`, data 없음.
- 실패:
  - `INVALID_CREDENTIALS`(401) — LOCAL 계정의 비밀번호 불일치 또는 미전송 (신규 ErrorCode 없음, 기존 재사용)
  - `USER_NOT_FOUND`(404) — 토큰의 userId로 유저 조회 실패
- 성공 시 프론트: 저장된 토큰 전부 삭제 후 홈/로그인 화면으로 이동.

## 삭제 흐름 (WithdrawalService.withdraw(userId, password) — 단일 @Transactional)

1. **유저 조회** — `userRepository.findById` → 없으면 `USER_NOT_FOUND`
2. **본인확인** — `user.getProvider() == LOCAL`이면 `passwordEncoder.matches(password, user.getPassword())` 검증, 실패/미전송 시 `INVALID_CREDENTIALS`. GOOGLE이면 생략
3. **댓글 익명화** — 벌크 업데이트: `UPDATE Comment c SET c.writer = '탈퇴한 사용자', c.author = null WHERE c.author = :user` (`CommentRepository`에 `@Modifying @Query` 메서드 추가)
4. **게시글 익명화** — `user.getLoginId() != null`일 때만: `UPDATE Board b SET b.writer = '탈퇴한 사용자' WHERE b.writer = :loginId` (`BoardRepository`에 추가. Board는 User FK가 없고 loginId 문자열로만 연결되어 있음. GOOGLE 계정은 loginId가 없으므로 해당 없음)
5. **refreshToken 전부 삭제** — `refreshTokenRepository.deleteByUser(user)` (기존 메서드) → 모든 기기에서 로그아웃 효과
6. **동의 이력 삭제** — `userConsentRepository.deleteByUser(user)` (신규 메서드)
7. **유저 행 삭제** — `userRepository.delete(user)`
8. **Redis 정리** (트랜잭션 커밋과 무관한 best-effort, 유저 삭제 후 수행):
   - `loginAttemptService.clearLockState(loginId)` — loginId 있을 때만 (잠금/실패 카운터 제거)
   - `emailVerificationService.clearVerified(email)` — **중요**: 탈퇴 직후 같은 이메일로 재가입할 때 이전 인증완료 플래그(30분 유효)가 남아있으면 이메일 인증을 건너뛰고 가입되는 구멍 차단

익명화 문자열 `"탈퇴한 사용자"`는 `WithdrawalService` 상수로 정의.

## 명시적 경계 사항

- **이미 발급된 accessToken은 만료(최대 30분) 전까지 기술적으로 유효** — stateless JWT 구조상 즉시 무효화 불가. 대부분의 API는 삭제된 userId 조회 시 `USER_NOT_FOUND`로 실패하므로 실효 위험 낮음. refreshToken은 삭제되어 갱신 불가. (비밀번호 재설정의 기존 수용 리스크와 동일한 프로파일)
- **재가입 시 완전한 신규 회원** — 탈퇴 이력을 남기지 않으므로(hard delete) 이전 활동과 연결 불가. 의도된 동작.
- **Board.writer의 구조적 한계** — Board는 FK 없이 loginId 문자열로만 작성자를 기록하므로, 만약 탈퇴자와 동일한 문자열 writer를 가진 데이터가 있다면 같이 익명화된다 (loginId는 unique 제약이 있어 실제 충돌 불가, 이론상 한계만 기록).
- **감사/분쟁 대응용 최소 보존 없음** — 법령상 보존의무 데이터(결제 기록 등)가 이 서비스에 존재하지 않으므로 전량 즉시 파기가 적법.

## 코드 추가/변경 항목

| 파일 | 변경 |
|---|---|
| `SuccessCode` | `USER_WITHDRAWN("회원 탈퇴가 완료되었습니다.")` 추가 |
| `user/dto/WithdrawalRequest.java` (신규) | `password` 필드 (optional) |
| `user/service/WithdrawalService.java` (신규) | 위 삭제 흐름. 의존성: `UserRepository`, `CommentRepository`, `BoardRepository`, `RefreshTokenRepository`, `UserConsentRepository`, `PasswordEncoder`, `LoginAttemptService`, `EmailVerificationService` |
| `comment/repository/CommentRepository` | `@Modifying` 익명화 벌크 업데이트 추가 |
| `board/repository/BoardRepository` | `@Modifying` writer 교체 벌크 업데이트 추가 |
| `user/repository/UserConsentRepository` | `deleteByUser(User)` 추가 |
| `user/controller/MyPageController` | `@PostMapping("/withdrawal")` 추가 (`/users/me` 프리픽스 재사용) |
| ErrorCode | 변경 없음 (기존 재사용) |

## 테스트

- `WithdrawalServiceTest` (신규, Mockito):
  - LOCAL + 비밀번호 일치 → 익명화/삭제/Redis 정리 전 단계 호출 검증 (호출 순서 포함: 동의이력·토큰 삭제가 유저 삭제보다 먼저)
  - LOCAL + 비밀번호 불일치/미전송 → `INVALID_CREDENTIALS`, 어떤 삭제도 실행 안 됨
  - GOOGLE → 비밀번호 검증 없이 진행, board 익명화 스킵(loginId null), `clearLockState` 스킵
  - 없는 userId → `USER_NOT_FOUND`
- `MyPageControllerTest` (신규 파일 — 기존에 없음, `ConsentControllerTest`의 SecurityContext 패턴 재사용): 탈퇴 200, 비번 불일치 401
- 기존 테스트 영향 없음 (새 생성자 의존성이 기존 서비스에 추가되지 않음 — `WithdrawalService`는 완전 신규)

## 문서

`docs/API_REFERENCE.md`:
- 18번 엔드포인트로 탈퇴 API 추가 (LOCAL/GOOGLE 분기, 성공 시 토큰 폐기 안내, 게시글/댓글은 "탈퇴한 사용자"로 표시됨을 명시)
- 16번(동의 현황)의 "필수 동의(TERMS/PRIVACY)는 변경 API가 없음 — 철회하려면 회원탈퇴 절차 필요" 문구가 이제 실제 기능으로 연결됨
