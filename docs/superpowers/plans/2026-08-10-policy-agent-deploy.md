# Policy Agent 클라우드 배포 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `AI_agent`(정책·자금지원 추천 FastAPI) 저장소를 기존 `vision-ai`/`ranking-ml`과 동일한 패턴(내부 전용 ECS Fargate + Cloud Map + GitHub Actions OIDC)으로 AWS에 배포하고, 백엔드가 `policy-agent.solaraivle.internal:8003`을 호출하도록 연결한다.

**Architecture:** `AI_agent` 레포에 Dockerfile과 GitHub Actions 워크플로를 추가해 ECR에 이미지를 올리고, 신규 보안그룹으로 백엔드 태스크에서만 접근 가능한 ECS 서비스를 Cloud Map에 등록한다. 백엔드 ECS 태스크 정의에 `POLICY_AGENT_URL`/시크릿을 새 리비전으로 추가해 배포한다. 코드 변경은 인프라/설정뿐이며 애플리케이션 코드(`PolicyAgentClient` 등)는 이미 완성되어 있다.

**Tech Stack:** FastAPI/Docker(정책 에이전트), AWS ECS Fargate, ECR, Cloud Map, Secrets Manager, IAM(OIDC), GitHub Actions, AWS CLI

## Global Constraints

- AWS 계정 `251917136397`, 리전 `ap-northeast-2` (spec: 핵심 결정사항)
- 클러스터는 기존 `solaraivle-cluster` 재사용, 신규 클러스터 생성 금지
- 신규 시크릿 값은 화면 출력·커밋에 평문 노출 금지 (spec: 핵심 결정사항 — 내부 API 키)
- 컴퓨팅 사양 0.5vCPU/1GB, desired count 1 — 오토스케일링/다중AZ 구성 안 함 (spec: 범위 — 제외)
- 컨테이너/ALB 헬스체크 별도 구성 안 함 — ECS 태스크 생존 여부로만 관리 (spec: 명시적 경계 사항)
- ALB로 외부 노출하지 않음 — 백엔드 태스크 보안그룹(`sg-0cc4a287a50eefc0f`)에서만 인바운드 허용 (spec: 핵심 결정사항)
- `AI_SERVER_URL` 관련 정리는 이번 범위 아님 — 손대지 않음 (spec: 명시적 경계 사항)

**참고 리소스 ID (기존 인프라, 재사용):**
- VPC: `vpc-04f1b96d8fd3919f1`
- 퍼블릭 서브넷: `subnet-03913caae6189e6db`(2a), `subnet-0f679a21dca2d3374`(2c)
- 백엔드 ECS 태스크 보안그룹: `sg-0cc4a287a50eefc0f` (`solaraivle-ecs-task-sg`)
- Cloud Map 네임스페이스: `ns-tgw5erp2opozqbuv` (`solaraivle.internal`)
- 실행 역할: `arn:aws:iam::251917136397:role/solaraivleEcsTaskExecutionRole`
- 태스크 역할: `arn:aws:iam::251917136397:role/solaraivleTaskRole`
- GitHub Actions 배포 역할: `arn:aws:iam::251917136397:role/solaraivle-github-actions-deploy`
- 기존 OpenAI 시크릿: `arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/openai-api-key-MNBI0m`
- ECS 클러스터: `solaraivle-cluster`

---

## File Structure

**`AI_agent` 저장소** (별도 GitHub 레포, `https://github.com/AIVLE-big-project-19/AI_agent.git`. 로컬에 없으므로 `C:\Users\User\Desktop\AI_agent`에 새로 클론해서 작업):
- Create: `Dockerfile` — 컨테이너 빌드 정의
- Create: `.github/workflows/deploy.yml` — CI/CD (backend `deploy.yml` 포팅)

**`backEnd` 저장소** (현재 작업 디렉터리 `C:\Users\User\Desktop\빅프로젝트`):
- Modify: `deploy/ecs-task-def.json` — 라이브 태스크 정의로 전체 동기화 + `POLICY_AGENT_URL`/시크릿 추가
- Modify: `deploy/README.md` — 배포 상태 갱신

**AWS 리소스** (코드 파일 없음, AWS CLI로 직접 생성 — 각 태스크의 검증 커맨드가 "테스트" 역할):
- ECR 리포지토리, CloudWatch 로그 그룹, 보안그룹, Secrets Manager 시크릿, IAM 정책, ECS 태스크 정의/서비스, Cloud Map 서비스

---

### Task 1: `AI_agent` 레포에 Dockerfile 추가 + 로컬 빌드 검증

**Files:**
- Create: `C:\Users\User\Desktop\AI_agent\Dockerfile`

**Interfaces:**
- Consumes: 없음 (첫 태스크)
- Produces: 로컬에서 빌드 가능한 Docker 이미지 `policy-agent-local:bootstrap` — Task 2가 이 빌드 산출물을 ECR로 push

- [ ] **Step 1: 저장소를 로컬에 클론**

```bash
cd "C:\Users\User\Desktop"
git clone https://github.com/AIVLE-big-project-19/AI_agent.git
cd AI_agent
git checkout -b infra/ecs-deploy
```

- [ ] **Step 2: 클론된 구조 확인 (Dockerfile이 없는 상태인지 확인)**

Run: `ls "C:\Users\User\Desktop\AI_agent"`
Expected: `app`, `data`, `requirements.txt`, `README.md`는 있지만 `Dockerfile`은 없음

- [ ] **Step 3: Dockerfile 작성**

`C:\Users\User\Desktop\AI_agent\Dockerfile`:

```dockerfile
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app app
COPY data data

EXPOSE 8003

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8003"]
```

- [ ] **Step 4: 로컬 빌드**

```bash
cd "C:\Users\User\Desktop\AI_agent"
docker build -t policy-agent-local:bootstrap .
```

Expected: 빌드 성공 (`Successfully tagged policy-agent-local:bootstrap` 또는 마지막 레이어 완료 메시지)

- [ ] **Step 5: 로컬 컨테이너 실행 후 헬스체크로 검증**

```bash
docker run -d --name policy-agent-test -p 8003:8003 \
  -e USE_LLM=false -e INTERNAL_API_KEY=test \
  policy-agent-local:bootstrap
sleep 3
curl -sf http://localhost:8003/health
docker rm -f policy-agent-test
```

Expected: `curl`이 `{"status":"UP",...}` JSON을 반환 (agent_service가 정책 JSON 로드에 성공했다는 뜻). 실패 시 `docker logs policy-agent-test`로 원인 확인 후 Dockerfile 또는 데이터 경로 문제 해결.

- [ ] **Step 6: 커밋**

```bash
cd "C:\Users\User\Desktop\AI_agent"
git add Dockerfile
git commit -m "feat: ECS Fargate 배포용 Dockerfile 추가"
```

---

### Task 2: ECR 리포지토리 생성 + 부트스트랩 이미지 push

**Files:** 없음 (AWS 리소스만 생성)

**Interfaces:**
- Consumes: Task 1의 로컬 이미지 `policy-agent-local:bootstrap`
- Produces: ECR 이미지 URI `251917136397.dkr.ecr.ap-northeast-2.amazonaws.com/solaraivle-policy-agent:bootstrap` — Task 5(태스크 정의 등록)가 이 URI를 사용

- [ ] **Step 1: ECR 리포지토리가 아직 없는지 확인**

Run: `aws ecr describe-repositories --repository-names solaraivle-policy-agent --region ap-northeast-2`
Expected: `RepositoryNotFoundException` 에러 (아직 없어야 정상)

- [ ] **Step 2: ECR 리포지토리 생성**

```bash
aws ecr create-repository \
  --repository-name solaraivle-policy-agent \
  --region ap-northeast-2
```

- [ ] **Step 3: 생성 확인**

Run: `aws ecr describe-repositories --repository-names solaraivle-policy-agent --region ap-northeast-2 --query "repositories[0].repositoryUri" --output text`
Expected: `251917136397.dkr.ecr.ap-northeast-2.amazonaws.com/solaraivle-policy-agent`

- [ ] **Step 4: ECR 로그인 후 부트스트랩 이미지 태깅 및 push**

```bash
aws ecr get-login-password --region ap-northeast-2 | \
  docker login --username AWS --password-stdin 251917136397.dkr.ecr.ap-northeast-2.amazonaws.com

docker tag policy-agent-local:bootstrap \
  251917136397.dkr.ecr.ap-northeast-2.amazonaws.com/solaraivle-policy-agent:bootstrap

docker push 251917136397.dkr.ecr.ap-northeast-2.amazonaws.com/solaraivle-policy-agent:bootstrap
```

- [ ] **Step 5: push 확인**

Run: `aws ecr describe-images --repository-name solaraivle-policy-agent --region ap-northeast-2 --query "imageDetails[].imageTags"`
Expected: `[["bootstrap"]]`

---

### Task 3: 로그 그룹 / 보안그룹 / 내부 API 키 시크릿 생성

**Files:** 없음 (AWS 리소스만 생성)

**Interfaces:**
- Consumes: `sg-0cc4a287a50eefc0f`(백엔드 태스크 SG, Global Constraints 참고), `vpc-04f1b96d8fd3919f1`
- Produces: 로그 그룹 `/ecs/solaraivle-policy-agent`, 보안그룹 ID(신규), 시크릿 이름 `solaraivle/policy-agent-internal-api-key` — Task 4(IAM), Task 5(태스크 정의), Task 6(ECS 서비스)가 이 값들을 참조

- [ ] **Step 1: CloudWatch 로그 그룹 생성**

```bash
aws logs create-log-group --log-group-name /ecs/solaraivle-policy-agent --region ap-northeast-2
```

Run 확인: `aws logs describe-log-groups --log-group-name-prefix /ecs/solaraivle-policy-agent --query "logGroups[0].logGroupName" --output text`
Expected: `/ecs/solaraivle-policy-agent`

- [ ] **Step 2: 보안그룹 생성**

```bash
aws ec2 create-security-group \
  --group-name solaraivle-policy-agent-sg \
  --description "Policy Agent ECS task - inbound from backend task only" \
  --vpc-id vpc-04f1b96d8fd3919f1 \
  --region ap-northeast-2
```

이 커맨드의 출력 JSON에서 `GroupId`를 기록해둔다 (이후 스텝에서 `SG_ID`로 참조).

- [ ] **Step 3: 인바운드 규칙 추가 (8003번 포트, 백엔드 태스크 SG에서만)**

```bash
SG_ID=$(aws ec2 describe-security-groups \
  --filters "Name=group-name,Values=solaraivle-policy-agent-sg" \
  --query "SecurityGroups[0].GroupId" --output text --region ap-northeast-2)

aws ec2 authorize-security-group-ingress \
  --group-id "$SG_ID" \
  --protocol tcp --port 8003 \
  --source-group sg-0cc4a287a50eefc0f \
  --region ap-northeast-2
```

- [ ] **Step 4: 보안그룹 규칙 확인**

Run: `aws ec2 describe-security-groups --group-ids "$SG_ID" --query "SecurityGroups[0].IpPermissions" --region ap-northeast-2`
Expected: `FromPort: 8003, ToPort: 8003`, `UserIdGroupPairs[0].GroupId: sg-0cc4a287a50eefc0f`

- [ ] **Step 5: 내부 API 키 시크릿 생성 (랜덤 값, 화면에 값 노출 안 함)**

```bash
aws secretsmanager create-secret \
  --name solaraivle/policy-agent-internal-api-key \
  --secret-string "$(openssl rand -hex 32)" \
  --region ap-northeast-2
```

- [ ] **Step 6: 시크릿 생성 확인 (값이 아니라 존재 여부만 확인)**

Run: `aws secretsmanager describe-secret --secret-id solaraivle/policy-agent-internal-api-key --query ARN --output text --region ap-northeast-2`
Expected: `arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/policy-agent-internal-api-key-XXXXXX` 형식의 ARN

---

### Task 4: IAM 갱신 (GitHub Actions trust policy, ECR/시크릿 권한)

**Files:** 없음 (IAM 정책만 갱신)

**Interfaces:**
- Consumes: Task 2의 ECR 리포지토리 ARN, Task 3의 시크릿 ARN
- Produces: `solaraivle-github-actions-deploy`가 `AI_agent` 레포에서 배포 가능, `solaraivleEcsTaskExecutionRole`이 새 시크릿을 읽을 수 있음 — Task 6(ECS 서비스), Task 7(CI)이 이 권한에 의존

- [ ] **Step 1: 현재 trust policy 백업 및 확인**

```bash
aws iam get-role --role-name solaraivle-github-actions-deploy \
  --query "Role.AssumeRolePolicyDocument" > /tmp/trust-policy-before.json
cat /tmp/trust-policy-before.json
```

- [ ] **Step 2: trust policy에 `AI_agent` 레포 추가한 새 문서 작성**

`/tmp/trust-policy-new.json` (Windows 환경이면 스크래치 디렉터리에 작성):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::251917136397:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:sub": [
            "repo:AIVLE-big-project-19/backEnd:*",
            "repo:AIVLE-big-project-19*/backEnd*:*",
            "repo:AIVLE-big-project-19/chat_bot:*",
            "repo:AIVLE-big-project-19*/chat_bot*:*",
            "repo:AIVLE-big-project-19/Ranking_ML:*",
            "repo:AIVLE-big-project-19*/Ranking_ML*:*",
            "repo:AIVLE-big-project-19/visionAI:*",
            "repo:AIVLE-big-project-19*/visionAI*:*",
            "repo:AIVLE-big-project-19/AI_agent:*",
            "repo:AIVLE-big-project-19*/AI_agent*:*"
          ]
        }
      }
    }
  ]
}
```

- [ ] **Step 3: trust policy 적용**

```bash
aws iam update-assume-role-policy \
  --role-name solaraivle-github-actions-deploy \
  --policy-document file:///tmp/trust-policy-new.json
```

- [ ] **Step 4: 인라인 정책(`solaraivle-deploy-permissions`)에 신규 ECR 리포지토리 ARN 추가한 새 문서 작성**

`/tmp/deploy-permissions-new.json`:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "EcrAuth",
      "Effect": "Allow",
      "Action": "ecr:GetAuthorizationToken",
      "Resource": "*"
    },
    {
      "Sid": "EcrPush",
      "Effect": "Allow",
      "Action": [
        "ecr:BatchCheckLayerAvailability",
        "ecr:GetDownloadUrlForLayer",
        "ecr:BatchGetImage",
        "ecr:PutImage",
        "ecr:InitiateLayerUpload",
        "ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload"
      ],
      "Resource": [
        "arn:aws:ecr:ap-northeast-2:251917136397:repository/solaraivle-backend",
        "arn:aws:ecr:ap-northeast-2:251917136397:repository/solaraivle-chatbot",
        "arn:aws:ecr:ap-northeast-2:251917136397:repository/solaraivle-ranking-ml",
        "arn:aws:ecr:ap-northeast-2:251917136397:repository/solaraivle-vision-ai",
        "arn:aws:ecr:ap-northeast-2:251917136397:repository/solaraivle-policy-agent"
      ]
    },
    {
      "Sid": "EcsDeploy",
      "Effect": "Allow",
      "Action": [
        "ecs:DescribeServices",
        "ecs:DescribeTaskDefinition",
        "ecs:RegisterTaskDefinition",
        "ecs:UpdateService"
      ],
      "Resource": "*"
    },
    {
      "Sid": "PassRolesToEcs",
      "Effect": "Allow",
      "Action": "iam:PassRole",
      "Resource": [
        "arn:aws:iam::251917136397:role/solaraivleEcsTaskExecutionRole",
        "arn:aws:iam::251917136397:role/solaraivleTaskRole"
      ]
    }
  ]
}
```

- [ ] **Step 5: 인라인 정책 적용**

```bash
aws iam put-role-policy \
  --role-name solaraivle-github-actions-deploy \
  --policy-name solaraivle-deploy-permissions \
  --policy-document file:///tmp/deploy-permissions-new.json
```

- [ ] **Step 6: `solaraivleEcsTaskExecutionRole`의 시크릿 읽기 정책에 신규 시크릿 ARN 추가**

먼저 신규 시크릿의 정확한 ARN을 조회:

```bash
POLICY_AGENT_SECRET_ARN=$(aws secretsmanager describe-secret \
  --secret-id solaraivle/policy-agent-internal-api-key \
  --query ARN --output text --region ap-northeast-2)
echo "$POLICY_AGENT_SECRET_ARN"
```

`/tmp/secrets-read-new.json` (기존 9개 시크릿 ARN + 방금 조회한 ARN):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "secretsmanager:GetSecretValue",
      "Resource": [
        "arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/jwt-secret-2oKy22",
        "arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/vworld-api-key-XY6Et9",
        "arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/mail-username-PSMP8C",
        "arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/mail-password-eOWckj",
        "arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/google-client-id-4SJhRd",
        "arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/google-client-secret-ziQXC7",
        "arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/openai-api-key-MNBI0m",
        "arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:rds!db-66dc3722-d899-4e15-8191-d96b80fef766-LaTfwG",
        "arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/chatbot-database-url-CsIa2I",
        "<POLICY_AGENT_SECRET_ARN을 여기에 붙여넣기>"
      ]
    }
  ]
}
```

`<POLICY_AGENT_SECRET_ARN을 여기에 붙여넣기>`를 Step 6에서 조회한 실제 ARN 문자열로 치환한 뒤:

```bash
aws iam put-role-policy \
  --role-name solaraivleEcsTaskExecutionRole \
  --policy-name solaraivle-secrets-read \
  --policy-document file:///tmp/secrets-read-new.json
```

- [ ] **Step 7: 두 정책 모두 반영 확인**

```bash
aws iam get-role --role-name solaraivle-github-actions-deploy \
  --query "Role.AssumeRolePolicyDocument.Statement[0].Condition.StringLike" \
  | grep AI_agent

aws iam get-role-policy --role-name solaraivleEcsTaskExecutionRole \
  --policy-name solaraivle-secrets-read \
  --query "PolicyDocument.Statement[0].Resource" | grep policy-agent-internal-api-key
```

Expected: 두 grep 모두 결과가 출력됨(빈 결과가 아님)

---

### Task 5: ECS 태스크 정의 등록

**Files:** 없음 (AWS 리소스만 생성). 참고용 로컬 스크래치 파일: `C:\Users\User\AppData\Local\Temp\claude\...\scratchpad\policy-agent-task-def.json` (스크래치패드 경로 사용)

**Interfaces:**
- Consumes: Task 2 이미지 URI, Task 3 로그 그룹/시크릿 ARN, Task 4 IAM 권한
- Produces: 태스크 정의 패밀리 `solaraivle-policy-agent` (revision 1) — Task 6(ECS 서비스), Task 7(CI 워크플로)이 이 패밀리명을 참조

- [ ] **Step 1: 시크릿 ARN 조회 (Task 3에서 생성한 것)**

```bash
POLICY_AGENT_SECRET_ARN=$(aws secretsmanager describe-secret \
  --secret-id solaraivle/policy-agent-internal-api-key \
  --query ARN --output text --region ap-northeast-2)
echo "$POLICY_AGENT_SECRET_ARN"
```

- [ ] **Step 2: 태스크 정의 JSON 작성**

스크래치패드에 `policy-agent-task-def.json` 작성 (`<POLICY_AGENT_SECRET_ARN>`은 Step 1에서 조회한 실제 값으로 치환):

```json
{
  "family": "solaraivle-policy-agent",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "512",
  "memory": "1024",
  "executionRoleArn": "arn:aws:iam::251917136397:role/solaraivleEcsTaskExecutionRole",
  "taskRoleArn": "arn:aws:iam::251917136397:role/solaraivleTaskRole",
  "containerDefinitions": [
    {
      "name": "policy-agent",
      "image": "251917136397.dkr.ecr.ap-northeast-2.amazonaws.com/solaraivle-policy-agent:bootstrap",
      "portMappings": [
        { "containerPort": 8003, "protocol": "tcp" }
      ],
      "essential": true,
      "secrets": [
        { "name": "OPENAI_API_KEY", "valueFrom": "arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/openai-api-key-MNBI0m" },
        { "name": "INTERNAL_API_KEY", "valueFrom": "<POLICY_AGENT_SECRET_ARN>" }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/solaraivle-policy-agent",
          "awslogs-region": "ap-northeast-2",
          "awslogs-stream-prefix": "ecs"
        }
      }
    }
  ]
}
```

- [ ] **Step 3: 태스크 정의 등록**

```bash
aws ecs register-task-definition \
  --cli-input-json file://policy-agent-task-def.json \
  --region ap-northeast-2
```

- [ ] **Step 4: 등록 확인**

Run: `aws ecs describe-task-definition --task-definition solaraivle-policy-agent --query "taskDefinition.{family:family,revision:revision,image:containerDefinitions[0].image}" --region ap-northeast-2`
Expected: `family: solaraivle-policy-agent, revision: 1, image: ...solaraivle-policy-agent:bootstrap`

---

### Task 6: Cloud Map 서비스 + ECS 서비스 생성, 기동 검증

**Files:** 없음

**Interfaces:**
- Consumes: Task 5 태스크 정의, Task 3 보안그룹, Global Constraints의 서브넷/네임스페이스 ID
- Produces: 실행 중인 ECS 서비스 `solaraivle-policy-agent-svc`, DNS `policy-agent.solaraivle.internal:8003` — Task 8(백엔드 연결), Task 9(E2E 검증)가 이 DNS를 호출

- [ ] **Step 1: Cloud Map 서비스 생성**

```bash
aws servicediscovery create-service \
  --name policy-agent \
  --namespace-id ns-tgw5erp2opozqbuv \
  --dns-config "NamespaceId=ns-tgw5erp2opozqbuv,RoutingPolicy=MULTIVALUE,DnsRecords=[{Type=A,TTL=10}]" \
  --region ap-northeast-2
```

이 커맨드 출력의 `Service.Arn`을 기록 (`SERVICE_REGISTRY_ARN`).

- [ ] **Step 2: 보안그룹 ID 재조회**

```bash
SG_ID=$(aws ec2 describe-security-groups \
  --filters "Name=group-name,Values=solaraivle-policy-agent-sg" \
  --query "SecurityGroups[0].GroupId" --output text --region ap-northeast-2)
echo "$SG_ID"
```

- [ ] **Step 3: ECS 서비스 생성**

```bash
SERVICE_REGISTRY_ARN=$(aws servicediscovery list-services \
  --filters "Name=NAMESPACE_ID,Values=ns-tgw5erp2opozqbuv" \
  --query "Services[?Name=='policy-agent'].Arn | [0]" --output text --region ap-northeast-2)

aws ecs create-service \
  --cluster solaraivle-cluster \
  --service-name solaraivle-policy-agent-svc \
  --task-definition solaraivle-policy-agent \
  --desired-count 1 \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[subnet-03913caae6189e6db,subnet-0f679a21dca2d3374],securityGroups=[$SG_ID],assignPublicIp=ENABLED}" \
  --service-registries "registryArn=$SERVICE_REGISTRY_ARN" \
  --region ap-northeast-2
```

- [ ] **Step 4: 태스크가 RUNNING 상태가 될 때까지 대기 후 확인**

```bash
aws ecs wait services-stable \
  --cluster solaraivle-cluster \
  --services solaraivle-policy-agent-svc \
  --region ap-northeast-2

aws ecs describe-services \
  --cluster solaraivle-cluster \
  --services solaraivle-policy-agent-svc \
  --query "services[0].{status:status,running:runningCount,desired:desiredCount}" \
  --region ap-northeast-2
```

Expected: `status: ACTIVE, running: 1, desired: 1`

- [ ] **Step 5: CloudWatch 로그로 정상 기동 확인**

```bash
aws logs tail /ecs/solaraivle-policy-agent --since 5m --region ap-northeast-2
```

Expected: `Uvicorn running on http://0.0.0.0:8003`, `OPENAI_API_KEY EXISTS: True` 로그 라인이 보임(에러 스택트레이스 없음)

---

### Task 7: `AI_agent` 레포에 GitHub Actions 워크플로 추가 + CI 배포 검증

**Files:**
- Create: `C:\Users\User\Desktop\AI_agent\.github\workflows\deploy.yml`

**Interfaces:**
- Consumes: Task 4의 IAM trust policy(`AI_agent` 레포 허용), Task 5/6의 태스크 정의·서비스 이름
- Produces: `main` push 시 자동 배포되는 CI 파이프라인 — 이후 정책 데이터/코드 변경 시 재사용

- [ ] **Step 1: 워크플로 파일 작성**

`C:\Users\User\Desktop\AI_agent\.github\workflows\deploy.yml`:

```yaml
name: Deploy to ECS

on:
  push:
    branches: [main]
  workflow_dispatch: {}

permissions:
  id-token: write
  contents: read

env:
  AWS_REGION: ap-northeast-2
  ECR_REPOSITORY: solaraivle-policy-agent
  ECS_CLUSTER: solaraivle-cluster
  ECS_SERVICE: solaraivle-policy-agent-svc
  CONTAINER_NAME: solaraivle-policy-agent

jobs:
  deploy:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Configure AWS credentials (OIDC)
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: arn:aws:iam::251917136397:role/solaraivle-github-actions-deploy
          aws-region: ${{ env.AWS_REGION }}

      - name: Login to Amazon ECR
        id: login-ecr
        uses: aws-actions/amazon-ecr-login@v2

      - name: Build and push image to ECR
        id: build-image
        env:
          ECR_REGISTRY: ${{ steps.login-ecr.outputs.registry }}
          IMAGE_TAG: ${{ github.sha }}
        run: |
          docker build -t $ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG .
          docker push $ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG
          echo "image=$ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG" >> "$GITHUB_OUTPUT"

      - name: Download current task definition
        run: |
          aws ecs describe-task-definition \
            --task-definition ${{ env.CONTAINER_NAME }} \
            --query taskDefinition \
            > task-definition.json

      - name: Render new task definition with the built image
        id: render-task-def
        uses: aws-actions/amazon-ecs-render-task-definition@v1
        with:
          task-definition: task-definition.json
          container-name: policy-agent
          image: ${{ steps.build-image.outputs.image }}

      - name: Deploy to ECS
        uses: aws-actions/amazon-ecs-deploy-task-definition@v2
        with:
          task-definition: ${{ steps.render-task-def.outputs.task-definition }}
          service: ${{ env.ECS_SERVICE }}
          cluster: ${{ env.ECS_CLUSTER }}
          wait-for-service-stability: true
```

**중요**: `container-name`은 `policy-agent`(Task 5 태스크 정의의 `containerDefinitions[0].name`)이고, `CONTAINER_NAME` env는 `describe-task-definition`에 넘길 패밀리명이라 `solaraivle-policy-agent`다. 이 둘은 값이 다르므로 backend의 `deploy.yml`을 그대로 복사하면 안 되고 위와 같이 분리해서 써야 한다.

- [ ] **Step 2: 커밋, push, PR 생성 (Task 1의 Dockerfile 커밋도 같은 브랜치에 이미 있으므로 함께 포함됨)**

```bash
cd "C:\Users\User\Desktop\AI_agent"
git add .github/workflows/deploy.yml
git commit -m "ci: ECS 자동 배포 워크플로 추가"
git push origin infra/ecs-deploy
gh pr create --repo AIVLE-big-project-19/AI_agent \
  --base main --head infra/ecs-deploy \
  --title "ECS Fargate 배포: Dockerfile + GitHub Actions 워크플로" \
  --body "AI_agent를 solaraivle-cluster에 내부 전용 ECS Fargate 서비스로 배포하기 위한 Dockerfile과 CI/CD 워크플로 추가. Merge 시 main push 트리거로 실제 배포가 실행됨."
```

**여기서 멈춘다.** `main`에 머지하면 워크플로가 즉시 트리거되어 실제 프로덕션 배포가 실행된다 — PR을 생성한 뒤, 병합 여부는 사용자에게 명시적으로 확인받은 다음에만 진행한다(구현자 서브에이전트는 PR 생성까지만 하고 `DONE_WITH_CONCERNS`로 보고, 컨트롤러가 병합 확인을 받아 별도로 병합을 진행한다).

- [ ] **Step 3: (사용자 확인 후) PR 병합하여 워크플로 트리거**

```bash
gh pr merge --repo AIVLE-big-project-19/AI_agent infra/ecs-deploy --merge
```

- [ ] **Step 4: GitHub Actions 실행 결과 확인**

```bash
gh run list --repo AIVLE-big-project-19/AI_agent --limit 1
gh run watch --repo AIVLE-big-project-19/AI_agent
```

Expected: 워크플로가 성공(`success`)으로 완료됨. 실패 시 `gh run view --repo AIVLE-big-project-19/AI_agent --log-failed`로 원인 확인.

- [ ] **Step 5: 새 이미지로 서비스가 갱신됐는지 확인**

```bash
aws ecs describe-services --cluster solaraivle-cluster --services solaraivle-policy-agent-svc \
  --query "services[0].taskDefinition" --region ap-northeast-2
aws ecs describe-task-definition --task-definition solaraivle-policy-agent \
  --query "taskDefinition.containerDefinitions[0].image" --region ap-northeast-2
```

Expected: 이미지 태그가 `bootstrap`이 아니라 git commit SHA로 바뀌어 있음

---

### Task 8: 백엔드 ECS 태스크 정의에 Policy Agent 연결

**Files:** 없음 (AWS 리소스만 갱신, backEnd 소스코드 변경 없음 — `PolicyAgentClient`는 이미 구현되어 있음)

**Interfaces:**
- Consumes: Task 6의 Cloud Map DNS(`policy-agent.solaraivle.internal:8003`), Task 3의 시크릿 ARN
- Produces: 라이브 `solaraivle-backend` 태스크 정의 새 리비전 — Task 9(E2E 검증)이 이 배포 결과를 확인

- [ ] **Step 1: 현재 라이브 백엔드 태스크 정의 조회**

```bash
aws ecs describe-task-definition --task-definition solaraivle-backend \
  --query taskDefinition --region ap-northeast-2 > backend-task-def-current.json
cat backend-task-def-current.json
```

- [ ] **Step 2: 시크릿 ARN 조회**

```bash
POLICY_AGENT_SECRET_ARN=$(aws secretsmanager describe-secret \
  --secret-id solaraivle/policy-agent-internal-api-key \
  --query ARN --output text --region ap-northeast-2)
echo "$POLICY_AGENT_SECRET_ARN"
```

- [ ] **Step 3: `backend-task-def-current.json`을 편집**

기존 `containerDefinitions[0].environment` 배열에 다음 항목 추가:

```json
{ "name": "POLICY_AGENT_URL", "value": "http://policy-agent.solaraivle.internal:8003" }
```

기존 `containerDefinitions[0].secrets` 배열에 다음 항목 추가(`<POLICY_AGENT_SECRET_ARN>`은 Step 2 값으로 치환):

```json
{ "name": "POLICY_AGENT_INTERNAL_API_KEY", "valueFrom": "<POLICY_AGENT_SECRET_ARN>" }
```

`describe-task-definition` 출력에는 `register-task-definition`이 거부하는 읽기 전용 필드(`taskDefinitionArn`, `revision`, `status`, `requiresAttributes`, `compatibilities`, `registeredAt`, `registeredBy`)가 포함되어 있으므로, 편집 후 이 필드들을 모두 제거한다.

- [ ] **Step 4: 새 리비전 등록**

```bash
aws ecs register-task-definition \
  --cli-input-json file://backend-task-def-current.json \
  --region ap-northeast-2
```

- [ ] **Step 5: 서비스 업데이트 및 안정화 대기**

```bash
aws ecs update-service \
  --cluster solaraivle-cluster \
  --service solaraivle-backend-svc \
  --task-definition solaraivle-backend \
  --region ap-northeast-2

aws ecs wait services-stable \
  --cluster solaraivle-cluster \
  --services solaraivle-backend-svc \
  --region ap-northeast-2
```

- [ ] **Step 6: 새 리비전에 환경변수가 반영됐는지 확인**

```bash
aws ecs describe-task-definition --task-definition solaraivle-backend \
  --query "taskDefinition.containerDefinitions[0].environment[?name=='POLICY_AGENT_URL']" \
  --region ap-northeast-2
```

Expected: `[{"name": "POLICY_AGENT_URL", "value": "http://policy-agent.solaraivle.internal:8003"}]`

- [ ] **Step 7: 백엔드 자체가 정상 기동했는지 확인 (기존 헬스체크 엔드포인트)**

```bash
curl -sf https://d1iuhepb03p42r.cloudfront.net/api/actuator/health
```

Expected: `{"groups":["liveness","readiness"],"status":"UP"}`

---

### Task 9: 네트워크 연결 End-to-End 검증

**Files:** 없음

**Interfaces:**
- Consumes: Task 6의 서비스, Task 3의 보안그룹, Task 8의 백엔드 연결
- Produces: SG 규칙 + Cloud Map DNS가 실제로 동작함을 증명하는 검증 결과 (다음 태스크로 넘길 산출물 없음, 최종 확인 단계)

- [ ] **Step 1: 백엔드와 같은 보안그룹으로 1회성 curl 태스크 실행 (SG 인바운드 규칙 + DNS 확인용)**

```bash
aws ecs run-task \
  --cluster solaraivle-cluster \
  --launch-type FARGATE \
  --task-definition solaraivle-backend \
  --overrides '{"containerOverrides":[{"name":"solaraivle-backend","command":["sh","-c","curl -sf http://policy-agent.solaraivle.internal:8003/health || echo CURL_FAILED"]}]}' \
  --network-configuration "awsvpcConfiguration={subnets=[subnet-03913caae6189e6db],securityGroups=[sg-0cc4a287a50eefc0f],assignPublicIp=ENABLED}" \
  --region ap-northeast-2
```

이 커맨드 출력의 `tasks[0].taskArn`을 기록.

- [ ] **Step 2: 태스크 종료 대기 후 로그 확인**

```bash
aws ecs wait tasks-stopped --cluster solaraivle-cluster --tasks "<TASK_ARN>" --region ap-northeast-2
aws logs tail /ecs/solaraivle-backend --since 5m --region ap-northeast-2 | grep -A2 "policy-agent\|CURL_FAILED\|status"
```

Expected: `curl` 로그에 `{"status":"UP",...}` 형태의 JSON이 보이고 `CURL_FAILED`는 없음. `solaraivle-backend` 컨테이너에 `curl` 바이너리가 없어 실패하면(예: `sh: curl: not found`), 같은 방식으로 `--task-definition`을 `solaraivle-chatbot`(python 기반, `curl` 또는 `python -c`로 대체 가능)으로 바꿔 재시도한다.

- [ ] **Step 3: 실제 리포트 생성 플로우로 최종 확인 (선택 — 테스트 계정/유휴부지 데이터가 있는 경우)**

프론트엔드 또는 API 클라이언트로 유휴부지 분석 리포트 생성을 1건 요청하고, 응답에 `recommendedSubsidies`/`agentExplanation` 필드가 채워져 있는지 확인. 데이터가 없다면 이 스텝은 건너뛰고 Step 1~2의 네트워크 검증으로 충분하다고 기록.

---

### Task 10: `backEnd` 저장소 배포 문서 동기화

**Files:**
- Modify: `deploy/ecs-task-def.json`
- Modify: `deploy/README.md`

**Interfaces:**
- Consumes: Task 8에서 등록한 라이브 백엔드 태스크 정의 전체 내용
- Produces: 커밋된 최신 배포 문서 (마지막 태스크, 이후 산출물 없음)

- [ ] **Step 1: 라이브 태스크 정의를 문서용으로 조회 (읽기 전용 필드 제거)**

```bash
cd "C:\Users\User\Desktop\빅프로젝트"
aws ecs describe-task-definition --task-definition solaraivle-backend \
  --query taskDefinition --region ap-northeast-2 > /tmp/backend-live-taskdef.json
```

`/tmp/backend-live-taskdef.json`에서 `taskDefinitionArn`, `revision`, `status`, `requiresAttributes`, `compatibilities`, `registeredAt`, `registeredBy` 필드를 제거한 내용을 `deploy/ecs-task-def.json`에 그대로 반영한다(Task 8 Step 3과 동일한 편집 규칙).

- [ ] **Step 2: `deploy/ecs-task-def.json` 갱신 확인**

Run: `git diff --stat deploy/ecs-task-def.json`
Expected: 변경 사항 존재 (`FASTAPI_URL`, `ML_SERVER_URL`, `VITE_CHATBOT_PROXY_TARGET`, `AWS_S3_BUCKET`, `AWS_REGION`, `ADMIN_EMAIL`, `POLICY_AGENT_URL` 등이 추가된 상태)

- [ ] **Step 3: `deploy/README.md`에 Policy Agent 섹션 추가**

기존 "AI 서버 연동 미배포" 문단을 아래 내용으로 교체:

```markdown
- **AI 서버 연동 상태** — `vision-ai`, `ranking-ml`, `chatbot`, `policy-agent` 4개 서비스는 모두 `solaraivle-cluster`에 내부 전용 ECS 서비스로 배포되어 Cloud Map(`solaraivle.internal`)으로 연결됨. `AI_SERVER_URL`만 여전히 `localhost` placeholder로 남아 있음(별도 작업 필요).
```

- [ ] **Step 4: 커밋**

```bash
git add deploy/ecs-task-def.json deploy/README.md
git commit -m "docs: Policy Agent 배포 반영, ECS 태스크 정의 문서를 라이브 상태와 동기화"
```

---

## Self-Review 결과

**Spec coverage:**
- Dockerfile/워크플로 신규 작성 → Task 1, 7
- 내부 전용 ECS 서비스 + Cloud Map → Task 6
- 신규 AWS 리소스(ECR/로그그룹/SG/시크릿) → Task 2, 3
- IAM 갱신(trust policy, ECR/시크릿 권한) → Task 4
- 백엔드 연결(env/시크릿, 코드 변경 없음) → Task 8
- 문서 동기화(README/ecs-task-def.json 라이브 전체 반영) → Task 10
- 장애 시나리오(Policy Agent 미기동 시 백엔드 영향 없음)는 기존 코드에 이미 구현되어 있어 별도 태스크 없이 Task 9에서 확인만 수행 — spec의 "명시적 경계 사항"과 일치
- `AI_SERVER_URL` 미해결 상태는 의도적으로 범위 밖으로 유지(Global Constraints에 명시), 어떤 태스크도 건드리지 않음 — 일치

**Placeholder 스캔:** "TBD"/"나중에" 없음. Task 9 Step 3만 데이터 유무에 따라 선택적으로 건너뛸 수 있다고 명시했는데, 이는 실제 운영 데이터 의존성 때문이며 조건과 대안(Step 1~2로 충분)을 구체적으로 명시했으므로 플레이스홀더가 아님.

**타입/네이밍 일관성:** 컨테이너 이름 `policy-agent` vs 태스크 정의 패밀리 `solaraivle-policy-agent` 차이를 Task 7에서 명시적으로 경고 — 다른 서비스(`chatbot`, `ranking-ml`)와 동일한 네이밍 컨벤션. 시크릿 ARN(`POLICY_AGENT_SECRET_ARN`)은 Task 3에서 생성 후 Task 4, 5, 8에서 매번 새로 조회하도록 설계되어 세션 간 상태 공유 문제 없음.
