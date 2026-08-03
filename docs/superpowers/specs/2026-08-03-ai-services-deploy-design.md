# AI 서비스 3종 배포 (Vision AI / Ranking ML / 챗봇) 설계 문서

## 배경

`빅프로젝트`(백엔드/프론트)는 이미 AWS ECS Fargate / Amplify Hosting에 배포되어 실사용 검증까지 끝난 상태다. 팀에는 이와 별도로 3개의 AI 관련 저장소가 있다:

- **`visionAI`** — YOLO 세그멘테이션 기반 위성이미지 분석 FastAPI 서버. 백엔드의 `IdleLandReportService` → `VWorldImageClient`(위성이미지 조회) → `VisionAiClient.predict()`(`POST {fastapi.url}/predict`)로 이미 실제 연동 코드가 작성되어 있고, 응답 계약(`{predictions, annotated_image}`)도 최신 커밋 기준으로 정확히 일치한다. **배포만 하면 실제 기능이 바로 작동한다.**
- **`Ranking_ML`** — Rule-based 검토 + ML 적합도 예측/랭킹/SHAP FastAPI 서버. 백엔드의 `MlScoringClient`가 `POST /rank/{dataset_type}`을 이미 정확히 호출하고 있다. **배포만 하면 실제 기능(유휴부지 랭킹 조회)이 바로 작동한다.**
- **`chat_bot`** — 원래 Spring 백엔드 `chat` 패키지에 있던 챗봇 기능을 분리한 독립 FastAPI 서비스. 이미 백엔드에서 관련 코드(`OpenAiConfig`, `chat` 패키지 전체)가 삭제되었다. **프론트가 백엔드를 거치지 않고 이 서비스를 직접 호출하는 구조**이며, `chat_message` 테이블을 백엔드와 같은 RDS에서 그대로 재사용하고 `JWT_SECRET`도 백엔드와 동일해야 로그인 연동 채팅 기록이 이어진다. 프론트의 `chatApi.js`는 아직 옛날 방식(백엔드 경유)으로 남아있어 함께 수정이 필요하다.

세 서비스 모두 아직 어디에도 배포되지 않은 상태다. 이 문서는 이 셋을 기존 `solaraivle-cluster`(ECS)에 새 서비스로 추가 배포하는 설계를 다룬다.

## 범위

**포함:**
- `visionAI`, `Ranking_ML` — 내부 전용(비공개) ECS 서비스로 배포, 백엔드 env var(`fastapi.url`, `ml.server.base-url`) 연결
- `chat_bot` — 공개 ECS 서비스로 배포(기존 ALB 재사용, 경로 기반 라우팅), 프론트 `chatApi.js`가 직접 호출하도록 수정
- 세 저장소 각각에 Dockerfile 신규 작성
- 백엔드 IAM 실행 역할에 챗봇용 DB 시크릿 읽기 권한 추가

**제외:**
- `ai-agent` 저장소 (팀에서 삭제 예정이라고 확인됨 — 이번 배포 대상 아님, 이 저장소를 의존하는 백엔드 보고서 기능(`AiAnalysisClient`)도 이번엔 손대지 않음)
- Vision AI 응답을 프론트에 직접 노출하는 기능 등 새 사용자 기능 개발 (이미 존재하는 연동 코드를 "연결"만 함)
- 오토스케일링, 다중 AZ 이중화 (기존 백엔드 배포와 동일하게 단일 태스크로 시작)

## 핵심 결정사항

| 결정 | 선택 | 근거 |
|---|---|---|
| Vision AI / Ranking ML 노출 범위 | **내부 전용** (ALB 없음) | 백엔드만 호출하는 서버라 공개 불필요. ALB 안 만들어서 월 $16~20 절감, 공격 표면도 줄임 |
| 챗봇 노출 범위 | **공개** (기존 ALB 재사용) | 프론트가 직접 호출하는 구조. 새 ALB 대신 **기존 `solaraivle-alb`에 경로 기반 라우팅 규칙 추가**로 비용 절감 |
| 내부 서비스 탐색 방식 | **AWS Cloud Map** (private DNS) | 태스크 재배포 때마다 사설 IP가 바뀌므로, 안정적인 이름(`vision-ai.solaraivle.internal` 등)이 필요. 거의 무료이고 AWS 표준 방식 |
| 네트워크 배치 | 기존 백엔드와 동일하게 **퍼블릭 서브넷 + 제한적 보안그룹** | NAT Gateway/VPC 엔드포인트 없이 이미지 pull이 가능해야 해서(기존 백엔드 패턴 재사용), 인바운드만 보안그룹으로 제한해 사실상 비공개로 만듦 |
| 컴퓨팅 사양 | 셋 다 **0.5vCPU/1GB로 시작** | 실제 속도 측정 전 미리 크게 잡지 않음. 느리면 태스크 정의 숫자만 바꿔서 나중에 올리면 됨 |
| 모델 파일 전달 방식 | **Docker 이미지에 직접 포함** (COPY) | `bestv2.pt`(54.8MB), `.pkl` 파일들(총 25MB) 모두 관리 가능한 크기. S3+런타임 다운로드 같은 추가 인프라 불필요 |
| 챗봇 DB 접속 정보 | **`DATABASE_URL` 전체를 새 Secrets Manager 시크릿으로 생성** | `chat_bot`은 분리된 env var가 아니라 완성된 연결 문자열(`mysql+pymysql://user:pass@host:port/db`) 하나만 받음 |
| 챗봇-백엔드 CORS/경로 분리 | 프론트에 **`VITE_CHATBOT_BASE_URL`** 새 환경변수 추가, 챗봇 라우트(`/chat`, `/chat/pdf`)는 경로 프리픽스 없이 그대로 호출 | 백엔드는 `/api` 컨텍스트패스가 있고 챗봇은 없어서, 같은 CloudFront 도메인이라도 경로가 겹치지 않아 ALB 규칙으로 명확히 구분 가능 |

## 컴포넌트 설계

### 1. 네트워킹/공통 인프라 (신규)

- **보안그룹 3개 신규 생성**:
  - `solaraivle-vision-ai-sg`: 인바운드 `8001/tcp` from `sg-0cc4a287a50eefc0f`(백엔드 태스크 SG)만
  - `solaraivle-ranking-ml-sg`: 인바운드 `8002/tcp` from `sg-0cc4a287a50eefc0f`만
  - `solaraivle-chatbot-sg`: 인바운드 `8010/tcp` from `sg-07633904e9eda70e2`(ALB SG)만
- **`sg-082df2ac50a9fb286`(RDS SG)에 규칙 추가**: 인바운드 `3306/tcp` from `solaraivle-chatbot-sg`
- **Cloud Map 네임스페이스**: `solaraivle.internal` (VPC `vpc-04f1b96d8fd3919f1`에 연결된 private DNS 네임스페이스)
  - 서비스 디스커버리 엔트리: `vision-ai`, `ranking-ml` (챗봇은 ALB로 노출되므로 Cloud Map 불필요)

### 2. Vision AI (`visionAI` 저장소, `infra/ecs-deploy` 브랜치)

- 신규 `Dockerfile`: Python 베이스 이미지 + `requirements.txt` 설치 + `app/` 코드 + `bestv2.pt` 모델 파일 COPY
- 컨테이너 포트 8001, `CMD uvicorn app.main:app --host 0.0.0.0 --port 8001`
- 환경변수: `MODEL_PATH=/app/bestv2.pt`
- ECS 태스크 정의: `solaraivle-vision-ai`, 0.5vCPU/1GB, 실행 역할 재사용(`solaraivleEcsTaskExecutionRole`), 태스크 역할 재사용(`solaraivleTaskRole`)
- ECS 서비스: desired count 1, Cloud Map 서비스 등록(`vision-ai.solaraivle.internal`), ALB 연결 없음
- **백엔드 `application.yaml`의 `fastapi.url` 기본값을 업데이트하지 않고, ECS 태스크 정의의 환경변수로 오버라이드**: `FASTAPI_URL=http://vision-ai.solaraivle.internal:8001` (로컬 개발 기본값 `http://localhost:8000`은 그대로 유지)

### 3. Ranking ML (`Ranking_ML` 저장소, `infra/ecs-deploy` 브랜치)

- 신규 `Dockerfile`: Python 베이스 + `requirements.txt` 설치 + 코드 + `Land_model_bundle.pkl`/`Building_model_bundle.pkl`/`Rule_base/`/`Merged_Test_Data.csv`/`태양광_RuleBase_조건.xlsx` COPY
- 컨테이너 포트 8002, `CMD uvicorn api.main:app --host 0.0.0.0 --port 8002`
- ECS 태스크 정의: `solaraivle-ranking-ml`, 0.5vCPU/1GB, 실행/태스크 역할 재사용
- ECS 서비스: desired count 1, Cloud Map 등록(`ranking-ml.solaraivle.internal`), ALB 연결 없음
- 백엔드 태스크 정의 환경변수 업데이트: `ML_SERVER_URL=http://ranking-ml.solaraivle.internal:8002`

### 4. 챗봇 (`chat_bot` 저장소, `infra/ecs-deploy` 브랜치)

- 신규 `Dockerfile`: Python 베이스 + `requirements.txt` 설치 + `app/` 코드
- 컨테이너 포트 8010, `CMD uvicorn app.main:app --host 0.0.0.0 --port 8010`
- **신규 Secrets Manager 시크릿**: `solaraivle/chatbot-database-url` — 값은 `mysql+pymysql://admin:<RDS 비밀번호>@solaraivle-db.c7ek60soyfx9.ap-northeast-2.rds.amazonaws.com:3306/solaraivle` (RDS 관리형 시크릿에서 비밀번호를 읽어와 조합, 화면/커밋에 평문 노출 안 함)
- 환경변수(시크릿): `DATABASE_URL`(신규), `JWT_SECRET`(기존 `solaraivle/jwt-secret` 재사용), `OPENAI_API_KEY`(기존 `solaraivle/openai-api-key` 재사용)
- 환경변수(평문): `CORS_ORIGINS=http://localhost:5173,https://main.d2bi30avd3chif.amplifyapp.com,https://infra-amplify-deploy.d2bi30avd3chif.amplifyapp.com`(백엔드 `CorsConfig`의 허용 origin 목록과 동일하게 맞춤), `PORT=8010`
- **`solaraivleEcsTaskExecutionRole`의 인라인 정책에 신규 `DATABASE_URL` 시크릿 ARN 추가** (기존 시크릿들은 이미 권한 있음)
- ECS 태스크 정의: `solaraivle-chatbot`, 0.5vCPU/1GB
- ECS 서비스: desired count 1, **`solaraivle-alb`의 기존 리스너(HTTP:80)에 타깃그룹 추가**
  - 신규 타깃그룹 `solaraivle-chatbot-tg` (protocol HTTP, port 8010, target type ip, health check `/health`)
  - 리스너 규칙: path pattern `/chat*` → `solaraivle-chatbot-tg` (기본 규칙보다 높은 우선순위). 그 외 전부(`/api/*` 포함) → 기존 `solaraivle-tg`(백엔드)

### 5. 프론트엔드 변경 (`frontEnd` 저장소, `infra/ai-services-deploy` 브랜치 — 저장소가 다르므로 백엔드와 브랜치명이 같아도 충돌 없음)

- `.env`/Amplify 환경변수에 `VITE_CHATBOT_BASE_URL=https://d1iuhepb03p42r.cloudfront.net` 추가 (같은 CloudFront 도메인, 경로로만 구분되므로 새 CloudFront 배포 불필요)
- `src/api/chatApi.js` 수정: 기존 `instance`(백엔드 axios, baseURL에 `/api` 포함) 대신, `VITE_CHATBOT_BASE_URL`을 baseURL로 하는 새 axios 인스턴스로 교체
  - `sendChatMessage`: `POST /chat`, body `{ message }` (기존과 동일한 요청 형식, 대상 서버만 변경)
  - `sendChatExcel` → **`sendChatPdf`로 이름 변경**, `POST /chat/pdf` (multipart, `file` + `message` 필드 — `chat_bot/app/routers/chat.py` 실제 시그니처 기준. 기존 `/chat/excel`은 존재하지 않는 경로이므로 그대로 두면 계속 실패함)
  - 이 함수를 호출하는 컴포넌트(`ChatBot.jsx` 등)에서 엑셀이 아니라 PDF 파일을 받도록 파일 입력 accept 속성도 함께 확인

## 명시적 경계 사항

- **`ai-agent` 저장소는 이번 배포 대상이 아니고, 그걸 의존하는 백엔드 보고서 기능(`AiAnalysisClient`)도 손대지 않는다.** 팀이 `ai-agent`를 삭제하기로 하면 그때 별도로 정리한다.
- Ranking ML은 `/health` 엔드포인트가 있고 Vision AI는 없다. 둘 다 ALB에 연결되지 않으므로(내부 전용) 이 차이는 무관하며, 컨테이너 헬스체크도 별도로 구성하지 않는다 — ECS가 태스크 생존 여부만으로 관리한다.
- 세 서비스 모두 오토스케일링 없음, desired count 1로 시작.
- `chat_bot`의 라우트는 `chat_bot/app/routers/chat.py` 기준으로 `POST /chat`, `POST /chat/pdf` 두 개뿐이며(`prefix="/chat"`), 헬스체크는 라우터 밖 앱 최상위의 `GET /health`다(즉 `/chat/health`가 아니라 `/health`).

## 테스트

- Vision AI: `curl http://vision-ai.solaraivle.internal:8001/predict` 는 클러스터 내부에서만 가능하므로, **실제 유휴부지 보고서 다운로드 기능**으로 엔드투엔드 검증(이미지 분석 결과가 PDF에 포함되는지)
- Ranking ML: 실제 유휴부지 랭킹 조회 API로 엔드투엔드 검증
- 챗봇: 타깃그룹 헬스체크(`/health`)는 ALB가 직접 대상으로 확인하므로 콘솔/CLI로 상태 확인. 외부 라우팅 검증은 `curl https://d1iuhepb03p42r.cloudfront.net/chat -X POST -H "Content-Type: application/json" -d '{"message":"안녕"}'`로 실제 응답이 오는지 확인 후, 프론트에서 실제 챗봇 대화로 최종 검증
