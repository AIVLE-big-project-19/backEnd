# Policy Agent (정책·자금지원 추천 AI) 배포 설계 문서

## 배경

`AI_agent` 저장소(`https://github.com/AIVLE-big-project-19/AI_agent.git`)는 태양광 정책·자금지원 추천을 담당하는 FastAPI 서버다. 백엔드의 `PolicyAgentClient`가 `POST {policy.agent.base-url}/api/v1/agent/analyze`를 이미 정확히 호출하도록 구현돼 있고(`836afcb feature: ai agent 연동 구현`), `ReportService`도 이 응답을 사용하도록 연동되어 있다. **배포만 하면 실제 기능이 바로 작동한다.**

이전 배포 문서(`2026-08-03-ai-services-deploy-design.md`)에서는 "`ai-agent` 저장소는 팀에서 삭제 예정이라 배포 대상 아님"으로 명시적으로 제외했었다. 이후 팀 결정이 바뀌어 이 저장소를 유지·배포하기로 했다 — 이 문서는 그 후속 배포를 다룬다.

이미 같은 클러스터(`solaraivle-cluster`)에 `vision-ai`, `ranking-ml`, `chatbot` 3개 AI 서비스가 동일한 패턴으로 배포되어 운영 중이다. 이번 배포는 그 패턴(내부 전용 ECS 서비스 + Cloud Map + GitHub Actions OIDC)을 그대로 따른다.

## 범위

**포함:**
- `AI_agent` 저장소에 `Dockerfile`, `.github/workflows/deploy.yml` 신규 작성
- 내부 전용(비공개) ECS Fargate 서비스로 배포 — 백엔드만 호출 가능
- 백엔드 ECS 태스크 정의에 `POLICY_AGENT_URL`, `POLICY_AGENT_INTERNAL_API_KEY` 추가(새 리비전)
- IAM(`solaraivle-github-actions-deploy`, `solaraivleEcsTaskExecutionRole`) 갱신
- 이 저장소(`backEnd`)의 `deploy/README.md`, `deploy/ecs-task-def.json`을 라이브 태스크 정의 기준으로 전체 동기화

**제외:**
- ALB를 통한 외부 노출 (백엔드 전용 내부 API라 불필요)
- 오토스케일링, 다중 AZ (기존 3개 서비스와 동일하게 desired count 1로 시작)
- `AI_SERVER_URL`(용도 불분명한 기존 placeholder) 정리 — 이번 범위 아님, 손대지 않음
- 컨테이너/ALB 헬스체크 구성 — 기존 내부 서비스(vision-ai, ranking-ml)와 동일하게 구성하지 않음. ECS가 태스크 생존 여부만으로 관리

## 핵심 결정사항

| 결정 | 선택 | 근거 |
|---|---|---|
| 노출 범위 | **내부 전용** (ALB 없음) | 백엔드만 호출하는 서버. 기존 vision-ai/ranking-ml과 동일 패턴 |
| 서비스 탐색 방식 | **AWS Cloud Map** (기존 `solaraivle.internal` 네임스페이스 재사용) | 태스크 재배포 시 사설 IP가 바뀌므로 안정적 DNS(`policy-agent.solaraivle.internal`) 필요. 신규 네임스페이스 생성 불필요 |
| 네트워크 배치 | 기존과 동일 **퍼블릭 서브넷 + 제한적 보안그룹** | NAT Gateway 없이 이미지 pull 가능해야 함(기존 패턴). 인바운드는 보안그룹으로 백엔드 태스크 SG(`sg-0cc4a287a50eefc0f`)만 허용 |
| 컴퓨팅 사양 | **0.5vCPU/1GB로 시작** | 기존 3개 서비스와 동일한 시작 사양. 느리면 태스크 정의 숫자만 조정 |
| OpenAI 키 | **기존 `solaraivle/openai-api-key` 시크릿 재사용** | chatbot도 같은 시크릿을 재사용 중, 신규 생성 불필요 |
| 내부 API 키 | **신규 Secrets Manager 시크릿 `solaraivle/policy-agent-internal-api-key`** | 백엔드 `PolicyAgentClient`가 `X-Internal-API-Key` 헤더로 보내고, Policy Agent가 검증하는 공유 값. 백엔드/Policy Agent 양쪽 태스크 정의에서 동일 시크릿 ARN 참조 |
| CI/CD 인증 | 기존 OIDC 역할(`solaraivle-github-actions-deploy`) trust policy에 `AI_agent` 레포 추가 | 새 키 발급/저장 불필요, 기존 방식 재사용 |
| 데이터 파일 전달 | **Docker 이미지에 직접 포함** (`data/태양광_정책통합_2026.json`, 수백 KB 수준) | 별도 인프라(S3 등) 불필요할 만큼 작음 |

## 컴포넌트 설계

### 1. `AI_agent` 저장소 변경

- 신규 `Dockerfile`: `python:3.11-slim` 베이스 + `requirements.txt` 설치 + `app/`, `data/` COPY
- 컨테이너 포트 8003, `CMD uvicorn app.main:app --host 0.0.0.0 --port 8003`
- 신규 `.github/workflows/deploy.yml`: backend의 `deploy.yml`을 포팅 — `main` push 시 (1) Docker 빌드·ECR push (2) 현재 태스크 정의 조회 후 이미지만 교체한 새 리비전 등록 (3) `solaraivle-policy-agent-svc` 업데이트. `ECR_REPOSITORY=solaraivle-policy-agent`, `ECS_SERVICE=solaraivle-policy-agent-svc`, `CONTAINER_NAME=policy-agent`로 변경
- 환경변수: `USE_LLM=true`(코드 기본값 유지), `OPENAI_API_KEY`/`INTERNAL_API_KEY`는 시크릿으로 주입. `LLM_FAILURE_MODE=FALLBACK`(코드 기본값 유지 — LLM 실패 시 결정론적 설명으로 폴백하므로 배포 초기 안정성에 유리)

### 2. 신규 AWS 리소스

- ECR 리포지토리 `solaraivle-policy-agent`
- CloudWatch 로그 그룹 `/ecs/solaraivle-policy-agent`
- 보안그룹 `solaraivle-policy-agent-sg`: 인바운드 `8003/tcp` from `sg-0cc4a287a50eefc0f`(백엔드 태스크 SG)만
- Cloud Map 서비스 `policy-agent` (네임스페이스: 기존 `solaraivle.internal`, `ns-tgw5erp2opozqbuv`) → DNS `policy-agent.solaraivle.internal`
- Secrets Manager 시크릿 `solaraivle/policy-agent-internal-api-key` (랜덤 값 신규 생성)
- ECS 태스크 정의 `solaraivle-policy-agent` (0.5vCPU/1GB, 실행 역할 `solaraivleEcsTaskExecutionRole`/태스크 역할 `solaraivleTaskRole` 재사용)
- ECS 서비스 `solaraivle-policy-agent-svc`: desired count 1, Cloud Map 등록, ALB 연결 없음, 퍼블릭 서브넷(`subnet-03913caae6189e6db`, `subnet-0f679a21dca2d3374`), `assignPublicIp: ENABLED`

### 3. IAM 갱신

- `solaraivle-github-actions-deploy` role:
  - trust policy `StringLike` 목록에 `repo:AIVLE-big-project-19/AI_agent:*`, `repo:AIVLE-big-project-19*/AI_agent*:*` 추가
  - 인라인 정책(`solaraivle-deploy-permissions`) `EcrPush` 리소스 목록에 `arn:aws:ecr:...repository/solaraivle-policy-agent` 추가
- `solaraivleEcsTaskExecutionRole`의 인라인 정책(`solaraivle-secrets-read`)에 신규 `solaraivle/policy-agent-internal-api-key` 시크릿 ARN 추가

### 4. 백엔드(`backEnd`) 연결

- 백엔드 코드 변경 없음(`PolicyAgentClient`가 이미 `policy.agent.base-url`/`policy.agent.internal-api-key`를 사용 중)
- 라이브 ECS 태스크 정의(`solaraivle-backend`)에 새 리비전 등록:
  - 환경변수 `POLICY_AGENT_URL=http://policy-agent.solaraivle.internal:8003`
  - 시크릿 `POLICY_AGENT_INTERNAL_API_KEY` → `solaraivle/policy-agent-internal-api-key` 시크릿 ARN
- 서비스 업데이트(`solaraivle-backend-svc`)로 새 리비전 반영

### 5. 문서 동기화 (`backEnd` 저장소)

- `deploy/ecs-task-def.json`을 라이브 태스크 정의 전체 내용으로 갱신 (현재 누락된 `FASTAPI_URL`, `ML_SERVER_URL`, `VITE_CHATBOT_PROXY_TARGET`, `AWS_S3_BUCKET`, `AWS_REGION`, `ADMIN_EMAIL` 등도 함께 반영 + 이번에 추가하는 `POLICY_AGENT_URL`/시크릿)
- `deploy/README.md`의 "AI 서버 연동 미배포" 문구를 실제 배포 상태(vision-ai/ranking-ml/chatbot/policy-agent 배포 완료, 나머지 `AI_SERVER_URL`만 미해결 placeholder)로 갱신

## 명시적 경계 사항

- `AI_SERVER_URL`(`http://localhost:9000` placeholder, 용도가 불분명한 별도 필드)은 이번 범위가 아니다 — `ai/server.base-url`을 참조하는 코드가 있는지, 있다면 어떤 기능인지 확인이 필요한 별개 작업이다.
- Policy Agent는 `/health` 엔드포인트가 있지만 ALB에 연결하지 않으므로(내부 전용) 컨테이너/타깃그룹 헬스체크를 별도로 구성하지 않는다 — 기존 vision-ai/ranking-ml과 동일하게 ECS가 태스크 생존 여부만으로 관리한다.
- 오토스케일링 없음, desired count 1로 시작.
- `data/태양광_정책통합_2026.json` 갱신(정책 데이터 업데이트) 시에는 이미지 재빌드가 필요하다 — 런타임에 외부에서 다시 읽어오는 구조가 아니다. 데이터 갱신 주기가 잦아지면 별도 검토가 필요하지만 이번 범위는 아니다.

## 테스트

- Policy Agent 자체: ECS 서비스 기동 후 CloudWatch 로그(`/ecs/solaraivle-policy-agent`)에서 `OPENAI_API_KEY EXISTS: True`, 정상 기동 로그 확인
- 백엔드 연결: 백엔드 새 리비전 배포 후, 실제 유휴부지 보고서 생성 플로우(정책·자금지원 추천 섹션이 포함된 리포트)로 엔드투엔드 검증 — `RecommendedSubsidy`, `AgentExplanation` 등 응답 필드가 채워지는지 확인
- 장애 시나리오: Policy Agent가 아직 배포되지 않은 상태에서도 백엔드 기동 자체는 영향받지 않아야 함(런타임 호출 시점에만 `CustomException(POLICY_AGENT_REQUEST_FAILED)` 발생) — 기존 코드에 이미 구현되어 있으므로 별도 작업 없이 확인만
