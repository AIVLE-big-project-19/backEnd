# AI 서비스 3종 배포 (Vision AI / Ranking ML / 챗봇) 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `visionAI`, `Ranking_ML`, `chat_bot` 세 저장소를 컨테이너화해서 기존 `solaraivle-cluster`(ECS Fargate)에 새 서비스로 배포하고, 이미 백엔드에 작성되어 있는 연동 코드(`VisionAiClient`, `MlScoringClient`)와 프론트 챗봇 UI가 실제로 이 서비스들과 통신하도록 연결한다.

**Architecture:** Vision AI/Ranking ML은 AWS Cloud Map(private DNS)으로만 노출되는 내부 전용 ECS 서비스, 챗봇은 기존 ALB(`solaraivle-alb`)에 경로 기반 라우팅(`/chat*`)으로 추가되는 공개 서비스. 세 서비스 모두 기존 백엔드와 같은 VPC/퍼블릭 서브넷에 배치하고 보안그룹으로 접근을 제한한다.

**Tech Stack:** FastAPI + Uvicorn (Python 3.11), Docker, AWS ECS Fargate, AWS Cloud Map, Application Load Balancer, Secrets Manager.

## Global Constraints

- AWS 계정: `251917136397`, 리전: `ap-northeast-2`
- 기존 VPC 재사용: `vpc-04f1b96d8fd3919f1`
- 기존 퍼블릭 서브넷 재사용: `subnet-03913caae6189e6db`(2a), `subnet-0f679a21dca2d3374`(2c) — 모든 신규 태스크는 `assignPublicIp=ENABLED`로 이 서브넷에 배치(이미지 pull을 위해 NAT 없이 인터넷 접근 필요)
- 기존 ECS 클러스터 재사용: `solaraivle-cluster`
- 기존 IAM 역할 재사용: 실행 역할 `arn:aws:iam::251917136397:role/solaraivleEcsTaskExecutionRole`, 태스크 역할 `arn:aws:iam::251917136397:role/solaraivleTaskRole`
- 기존 백엔드 태스크 보안그룹: `sg-0cc4a287a50eefc0f` (Vision AI/Ranking ML 인바운드 허용 대상)
- 기존 ALB 보안그룹: `sg-07633904e9eda70e2` (챗봇 인바운드 허용 대상)
- 기존 RDS 보안그룹: `sg-082df2ac50a9fb286`
- 기존 ALB: `solaraivle-alb`, ARN `arn:aws:elasticloadbalancing:ap-northeast-2:251917136397:loadbalancer/app/solaraivle-alb/782974e6c4737c8b`
- 기존 HTTP:80 리스너 ARN: `arn:aws:elasticloadbalancing:ap-northeast-2:251917136397:listener/app/solaraivle-alb/782974e6c4737c8b/47199baf3ecec9ba`
- 기존 백엔드 태스크 정의 family: `solaraivle-backend`
- RDS 엔드포인트: `solaraivle-db.c7ek60soyfx9.ap-northeast-2.rds.amazonaws.com:3306`, 마스터 사용자명 `admin`, 비밀번호는 Secrets Manager `arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:rds!db-66dc3722-d899-4e15-8191-d96b80fef766-LaTfwG`
- 기존 시크릿 재사용: JWT_SECRET = `arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/jwt-secret-2oKy22`, OPENAI_API_KEY = `arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/openai-api-key-MNBI0m`
- 모든 AWS CLI 명령은 PowerShell에서 `$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"` 먼저 실행 후 사용
- 프론트 배포 주소(CORS/허용 origin 목록에 사용): `http://localhost:5173`, `https://main.d2bi30avd3chif.amplifyapp.com`, `https://infra-amplify-deploy.d2bi30avd3chif.amplifyapp.com`
- 각 저장소 작업은 `main`에서 분기한 새 브랜치에서 진행하고, 완료 후 PR을 생성한다(머지는 사용자 승인 후)

---

### Task 1: 공통 인프라 — 보안그룹 + Cloud Map 네임스페이스

**Files:** 없음 (AWS 인프라 리소스만 생성)

**Interfaces:**
- Produces: 3개 보안그룹 ID(Vision AI/Ranking ML/챗봇용), Cloud Map 네임스페이스 ID, 네임스페이스 안의 Cloud Map 서비스 ARN 2개(`vision-ai`, `ranking-ml`) — 이후 모든 태스크에서 사용

- [ ] **Step 1: 보안그룹 3개 생성**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
$region = "ap-northeast-2"
$vpcId = "vpc-04f1b96d8fd3919f1"

$sgVision = (aws ec2 create-security-group --group-name solaraivle-vision-ai-sg --description "Vision AI inbound from backend task" --vpc-id $vpcId --tag-specifications 'ResourceType=security-group,Tags=[{Key=Name,Value=solaraivle-vision-ai-sg}]' --region $region --query 'GroupId' --output text)
$sgRanking = (aws ec2 create-security-group --group-name solaraivle-ranking-ml-sg --description "Ranking ML inbound from backend task" --vpc-id $vpcId --tag-specifications 'ResourceType=security-group,Tags=[{Key=Name,Value=solaraivle-ranking-ml-sg}]' --region $region --query 'GroupId' --output text)
$sgChatbot = (aws ec2 create-security-group --group-name solaraivle-chatbot-sg --description "Chatbot inbound from ALB" --vpc-id $vpcId --tag-specifications 'ResourceType=security-group,Tags=[{Key=Name,Value=solaraivle-chatbot-sg}]' --region $region --query 'GroupId' --output text)

Write-Output "SG_VISION=$sgVision"
Write-Output "SG_RANKING=$sgRanking"
Write-Output "SG_CHATBOT=$sgChatbot"
"$sgVision,$sgRanking,$sgChatbot" | Out-File -FilePath "$env:TEMP\ai_services_sg_ids.txt" -Encoding ascii -NoNewline
```

**Step 1 검증:** 각 변수에 `sg-`로 시작하는 ID가 출력되는지 확인.

- [ ] **Step 2: 인바운드 규칙 설정**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
$region = "ap-northeast-2"
$sgVision = "<Step 1의 SG_VISION 값>"
$sgRanking = "<Step 1의 SG_RANKING 값>"
$sgChatbot = "<Step 1의 SG_CHATBOT 값>"
$sgBackendTask = "sg-0cc4a287a50eefc0f"
$sgAlb = "sg-07633904e9eda70e2"
$sgRds = "sg-082df2ac50a9fb286"

aws ec2 authorize-security-group-ingress --group-id $sgVision --protocol tcp --port 8001 --source-group $sgBackendTask --region $region
aws ec2 authorize-security-group-ingress --group-id $sgRanking --protocol tcp --port 8002 --source-group $sgBackendTask --region $region
aws ec2 authorize-security-group-ingress --group-id $sgChatbot --protocol tcp --port 8010 --source-group $sgAlb --region $region
aws ec2 authorize-security-group-ingress --group-id $sgRds --protocol tcp --port 3306 --source-group $sgChatbot --region $region
Write-Output "ingress rules set"
```

**Step 2 검증:** 4개 명령 모두 `SecurityGroupRules` JSON을 반환하며 에러 없이 끝나는지 확인.

- [ ] **Step 3: Cloud Map 네임스페이스 생성 (비동기 — 완료까지 대기 필요)**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
$region = "ap-northeast-2"
$vpcId = "vpc-04f1b96d8fd3919f1"

$opId = (aws servicediscovery create-private-dns-namespace --name solaraivle.internal --vpc $vpcId --region $region --query 'OperationId' --output text)
Write-Output "OperationId=$opId"

do {
  Start-Sleep -Seconds 5
  $status = aws servicediscovery get-operation --operation-id $opId --region $region --query 'Operation.Status' --output text
  Write-Output "status=$status"
} while ($status -eq "PENDING" -or $status -eq "SUBMITTED")

$nsId = (aws servicediscovery list-namespaces --region $region --query "Namespaces[?Name=='solaraivle.internal'].Id" --output text)
Write-Output "NAMESPACE_ID=$nsId"
$nsId | Out-File -FilePath "$env:TEMP\ai_services_namespace_id.txt" -Encoding ascii -NoNewline
```

**Step 3 검증:** `status`가 `SUCCESS`로 끝나고, `NAMESPACE_ID`가 `ns-`로 시작하는 값으로 출력되는지 확인.

- [ ] **Step 4: Cloud Map 서비스(디스커버리 엔트리) 2개 생성**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
$region = "ap-northeast-2"
$nsId = "<Step 3의 NAMESPACE_ID 값>"

$cmVision = (aws servicediscovery create-service --name vision-ai --namespace-id $nsId --dns-config "NamespaceId=$nsId,DnsRecords=[{Type=A,TTL=10}]" --region $region --query 'Service.Arn' --output text)
$cmRanking = (aws servicediscovery create-service --name ranking-ml --namespace-id $nsId --dns-config "NamespaceId=$nsId,DnsRecords=[{Type=A,TTL=10}]" --region $region --query 'Service.Arn' --output text)

Write-Output "CM_VISION_ARN=$cmVision"
Write-Output "CM_RANKING_ARN=$cmRanking"
"$cmVision,$cmRanking" | Out-File -FilePath "$env:TEMP\ai_services_cloudmap_arns.txt" -Encoding ascii -NoNewline
```

**Step 4 검증:** 두 변수 모두 `arn:aws:servicediscovery:...`로 시작하는 값이 출력되는지 확인.

이 태스크에서 만든 값들(3개 SG ID, 네임스페이스 ID, 2개 Cloud Map 서비스 ARN)은 이후 모든 태스크에서 재사용하므로 기록해둔다.

---

### Task 2: Vision AI — Dockerfile 작성 + 이미지 빌드/검증 + ECR push

**Files:**
- Create: `visionAI/Dockerfile` (visionAI 저장소 루트)
- Create: `visionAI/.dockerignore`
- Copy: `C:\Users\User\Desktop\bestv2.pt` → `visionAI/bestv2.pt`

**Interfaces:**
- Consumes: 없음
- Produces: ECR 이미지 `251917136397.dkr.ecr.ap-northeast-2.amazonaws.com/solaraivle-vision-ai:v1`, 컨테이너는 포트 8001에서 `POST /predict`(multipart `image` + `extent3857`) 제공, 응답 `{predictions, annotated_image}`

- [ ] **Step 1: 브랜치 생성**

```bash
cd "C:\Users\User\Desktop\ai-repos-check\visionAI"
git checkout main
git pull
git checkout -b infra/ecs-deploy
```

- [ ] **Step 2: 모델 파일을 저장소 안으로 복사**

```bash
cp "C:\Users\User\Desktop\bestv2.pt" "C:\Users\User\Desktop\ai-repos-check\visionAI\bestv2.pt"
```

- [ ] **Step 3: Dockerfile 작성**

`visionAI/Dockerfile`:
```dockerfile
FROM python:3.11-slim
WORKDIR /app

# opencv-python-headless가 런타임에 필요로 하는 공유 라이브러리
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app app
COPY bestv2.pt .

ENV MODEL_PATH=/app/bestv2.pt

EXPOSE 8001
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]
```

- [ ] **Step 4: .dockerignore 작성**

`visionAI/.dockerignore`:
```
.git/
__pycache__/
*.pyc
.venv/
.env
```

- [ ] **Step 5: 로컬에서 이미지 빌드 검증**

```bash
cd "C:\Users\User\Desktop\ai-repos-check\visionAI"
docker build -t solaraivle-vision-ai:v1 .
```

Expected: `BUILD SUCCESSFUL`에 준하는 `Successfully tagged solaraivle-vision-ai:v1` 출력, 에러 없음. `ultralytics`/`opencv-python-headless` 설치 단계에서 실패하면 `libgl1 libglib2.0-0` 관련 시스템 패키지가 부족한 것이므로 Dockerfile의 apt-get 라인을 다시 확인.

- [ ] **Step 6: 로컬 컨테이너 실행으로 기동 확인**

```bash
docker run -d --name vision-ai-test -p 8001:8001 solaraivle-vision-ai:v1
sleep 15
docker logs vision-ai-test 2>&1 | tail -30
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8001/docs
docker rm -f vision-ai-test
```

Expected: `curl`이 `200`을 반환(FastAPI가 자동 생성하는 Swagger 문서 페이지). 로그에 `Uvicorn running on http://0.0.0.0:8001` 같은 라인이 있어야 함. 에러가 있으면(예: `MODEL_PATH` 못 찾음) Dockerfile의 COPY 순서/경로를 재확인.

- [ ] **Step 7: ECR 리포지토리 생성 + push**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
$region = "ap-northeast-2"
aws ecr create-repository --repository-name solaraivle-vision-ai --image-scanning-configuration scanOnPush=true --region $region
```

```bash
export PATH="$PATH:/c/Program Files/Amazon/AWSCLIV2"
aws ecr get-login-password --region ap-northeast-2 | docker login --username AWS --password-stdin 251917136397.dkr.ecr.ap-northeast-2.amazonaws.com
docker tag solaraivle-vision-ai:v1 251917136397.dkr.ecr.ap-northeast-2.amazonaws.com/solaraivle-vision-ai:v1
docker push 251917136397.dkr.ecr.ap-northeast-2.amazonaws.com/solaraivle-vision-ai:v1
```

Expected: push 명령 마지막 줄에 `v1: digest: sha256:... size: ...` 출력.

- [ ] **Step 8: 커밋**

```bash
cd "C:\Users\User\Desktop\ai-repos-check\visionAI"
git add Dockerfile .dockerignore bestv2.pt
git commit -m "feat: ECS 배포용 Dockerfile 추가"
git push -u origin infra/ecs-deploy
```

---

### Task 3: Vision AI — ECS 태스크 정의 + 서비스 생성

**Files:** 없음 (AWS 리소스만)

**Interfaces:**
- Consumes: Task 1의 `$sgVision`, Cloud Map 서비스 ARN(`CM_VISION_ARN`); Task 2의 ECR 이미지
- Produces: 실행 중인 ECS 서비스 `solaraivle-vision-ai-svc`, `vision-ai.solaraivle.internal:8001`로 클러스터 내부에서 접근 가능

- [ ] **Step 1: CloudWatch 로그 그룹 생성**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
aws logs create-log-group --log-group-name /ecs/solaraivle-vision-ai --region ap-northeast-2
```

- [ ] **Step 2: 태스크 정의 JSON 작성 및 등록**

`C:\Users\User\AppData\Local\Temp\claude\vision-ai-task-def.json` (Write 툴로 생성):
```json
{
  "family": "solaraivle-vision-ai",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "512",
  "memory": "1024",
  "executionRoleArn": "arn:aws:iam::251917136397:role/solaraivleEcsTaskExecutionRole",
  "taskRoleArn": "arn:aws:iam::251917136397:role/solaraivleTaskRole",
  "containerDefinitions": [
    {
      "name": "vision-ai",
      "image": "251917136397.dkr.ecr.ap-northeast-2.amazonaws.com/solaraivle-vision-ai:v1",
      "portMappings": [{ "containerPort": 8001, "protocol": "tcp" }],
      "essential": true,
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/solaraivle-vision-ai",
          "awslogs-region": "ap-northeast-2",
          "awslogs-stream-prefix": "ecs"
        }
      }
    }
  ]
}
```

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
aws ecs register-task-definition --cli-input-json file://C:/Users/User/AppData/Local/Temp/claude/vision-ai-task-def.json --region ap-northeast-2 --query 'taskDefinition.[family,revision]' --output text
```

- [ ] **Step 3: ECS 서비스 생성 (Cloud Map 등록)**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
$region = "ap-northeast-2"
$pub1 = "subnet-03913caae6189e6db"
$pub2 = "subnet-0f679a21dca2d3374"
$sgVision = "<Task 1의 SG_VISION 값>"
$cmVisionArn = "<Task 1의 CM_VISION_ARN 값>"

aws ecs create-service `
  --cluster solaraivle-cluster `
  --service-name solaraivle-vision-ai-svc `
  --task-definition solaraivle-vision-ai `
  --desired-count 1 `
  --launch-type FARGATE `
  --network-configuration "awsvpcConfiguration={subnets=[$pub1,$pub2],securityGroups=[$sgVision],assignPublicIp=ENABLED}" `
  --service-registries "registryArn=$cmVisionArn" `
  --region $region
```

- [ ] **Step 4: 서비스 안정화 대기 + 태스크 상태 확인**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
aws ecs wait services-stable --cluster solaraivle-cluster --services solaraivle-vision-ai-svc --region ap-northeast-2
aws ecs describe-services --cluster solaraivle-cluster --services solaraivle-vision-ai-svc --region ap-northeast-2 --query 'services[0].[status,runningCount,desiredCount]' --output text
```

Expected: `ACTIVE 1 1`. 안 되면 `aws ecs describe-tasks`로 `stoppedReason` 확인, CloudWatch 로그(`/ecs/solaraivle-vision-ai`)에서 스택트레이스 확인.

---

### Task 4: Ranking ML — Dockerfile 작성 + 이미지 빌드/검증 + ECR push

**Files:**
- Create: `Ranking_ML/Dockerfile`
- Create: `Ranking_ML/.dockerignore`

**Interfaces:**
- Consumes: 없음
- Produces: ECR 이미지 `251917136397.dkr.ecr.ap-northeast-2.amazonaws.com/solaraivle-ranking-ml:v1`, 컨테이너는 포트 8002에서 `POST /rank/{dataset_type}`, `POST /analyze/vision-json` 등 제공

- [ ] **Step 1: 브랜치 생성**

```bash
cd "C:\Users\User\Desktop\ai-repos-check\Ranking_ML"
git checkout main
git pull
git checkout -b infra/ecs-deploy
```

- [ ] **Step 2: Dockerfile 작성**

`Ranking_ML/Dockerfile`:
```dockerfile
FROM python:3.11-slim
WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8002
CMD ["uvicorn", "api.main:app", "--host", "0.0.0.0", "--port", "8002"]
```

- [ ] **Step 3: .dockerignore 작성**

`Ranking_ML/.dockerignore`:
```
.git/
__pycache__/
*.pyc
.venv/
.env
*.ipynb
```

- [ ] **Step 4: 로컬에서 이미지 빌드 검증**

```bash
cd "C:\Users\User\Desktop\ai-repos-check\Ranking_ML"
docker build -t solaraivle-ranking-ml:v1 .
```

Expected: `Successfully tagged solaraivle-ranking-ml:v1`. `shap`/`lightgbm` 설치가 오래 걸릴 수 있음(수 분) — 실패 시 에러 메시지를 보고 필요한 시스템 패키지(`build-essential` 등)를 Dockerfile에 추가.

- [ ] **Step 5: 로컬 컨테이너 실행으로 기동 확인**

```bash
docker run -d --name ranking-ml-test -p 8002:8002 solaraivle-ranking-ml:v1
sleep 10
docker logs ranking-ml-test 2>&1 | tail -30
curl -s http://localhost:8002/health
docker rm -f ranking-ml-test
```

Expected: `curl`이 `{"status": ...}` 형태의 JSON을 반환.

- [ ] **Step 6: ECR 리포지토리 생성 + push**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
aws ecr create-repository --repository-name solaraivle-ranking-ml --image-scanning-configuration scanOnPush=true --region ap-northeast-2
```

```bash
export PATH="$PATH:/c/Program Files/Amazon/AWSCLIV2"
aws ecr get-login-password --region ap-northeast-2 | docker login --username AWS --password-stdin 251917136397.dkr.ecr.ap-northeast-2.amazonaws.com
docker tag solaraivle-ranking-ml:v1 251917136397.dkr.ecr.ap-northeast-2.amazonaws.com/solaraivle-ranking-ml:v1
docker push 251917136397.dkr.ecr.ap-northeast-2.amazonaws.com/solaraivle-ranking-ml:v1
```

- [ ] **Step 7: 커밋**

```bash
cd "C:\Users\User\Desktop\ai-repos-check\Ranking_ML"
git add Dockerfile .dockerignore
git commit -m "feat: ECS 배포용 Dockerfile 추가"
git push -u origin infra/ecs-deploy
```

---

### Task 5: Ranking ML — ECS 태스크 정의 + 서비스 생성

**Files:** 없음

**Interfaces:**
- Consumes: Task 1의 `$sgRanking`, `CM_RANKING_ARN`; Task 4의 ECR 이미지
- Produces: 실행 중인 ECS 서비스 `solaraivle-ranking-ml-svc`, `ranking-ml.solaraivle.internal:8002`로 접근 가능

- [ ] **Step 1: 로그 그룹 생성**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
aws logs create-log-group --log-group-name /ecs/solaraivle-ranking-ml --region ap-northeast-2
```

- [ ] **Step 2: 태스크 정의 작성 및 등록**

`C:\Users\User\AppData\Local\Temp\claude\ranking-ml-task-def.json`:
```json
{
  "family": "solaraivle-ranking-ml",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "512",
  "memory": "1024",
  "executionRoleArn": "arn:aws:iam::251917136397:role/solaraivleEcsTaskExecutionRole",
  "taskRoleArn": "arn:aws:iam::251917136397:role/solaraivleTaskRole",
  "containerDefinitions": [
    {
      "name": "ranking-ml",
      "image": "251917136397.dkr.ecr.ap-northeast-2.amazonaws.com/solaraivle-ranking-ml:v1",
      "portMappings": [{ "containerPort": 8002, "protocol": "tcp" }],
      "essential": true,
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/solaraivle-ranking-ml",
          "awslogs-region": "ap-northeast-2",
          "awslogs-stream-prefix": "ecs"
        }
      }
    }
  ]
}
```

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
aws ecs register-task-definition --cli-input-json file://C:/Users/User/AppData/Local/Temp/claude/ranking-ml-task-def.json --region ap-northeast-2 --query 'taskDefinition.[family,revision]' --output text
```

- [ ] **Step 3: ECS 서비스 생성**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
$region = "ap-northeast-2"
$pub1 = "subnet-03913caae6189e6db"
$pub2 = "subnet-0f679a21dca2d3374"
$sgRanking = "<Task 1의 SG_RANKING 값>"
$cmRankingArn = "<Task 1의 CM_RANKING_ARN 값>"

aws ecs create-service `
  --cluster solaraivle-cluster `
  --service-name solaraivle-ranking-ml-svc `
  --task-definition solaraivle-ranking-ml `
  --desired-count 1 `
  --launch-type FARGATE `
  --network-configuration "awsvpcConfiguration={subnets=[$pub1,$pub2],securityGroups=[$sgRanking],assignPublicIp=ENABLED}" `
  --service-registries "registryArn=$cmRankingArn" `
  --region $region
```

- [ ] **Step 4: 안정화 대기 + 확인**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
aws ecs wait services-stable --cluster solaraivle-cluster --services solaraivle-ranking-ml-svc --region ap-northeast-2
aws ecs describe-services --cluster solaraivle-cluster --services solaraivle-ranking-ml-svc --region ap-northeast-2 --query 'services[0].[status,runningCount,desiredCount]' --output text
```

Expected: `ACTIVE 1 1`.

---

### Task 6: 백엔드 — Vision AI/Ranking ML 연결 (env var 업데이트 + 재배포)

**Files:**
- 코드 변경 없음 — 백엔드는 `${FASTAPI_URL}`/`${ML_SERVER_URL}` 환경변수를 이미 읽도록 되어 있음(각각 `application.yaml`의 `fastapi.url`, `ml.server.base-url`에 대응)

**Interfaces:**
- Consumes: Task 3의 `vision-ai.solaraivle.internal:8001`, Task 5의 `ranking-ml.solaraivle.internal:8002`
- Produces: 백엔드 ECS 태스크 정의 새 리비전, 실제 유휴부지 보고서/랭킹 기능이 Vision AI/Ranking ML을 호출

- [ ] **Step 1: 업데이트된 백엔드 태스크 정의 파일을 그대로 작성**

`C:\Users\User\AppData\Local\Temp\claude\backend-task-def-updated.json` (Write 툴로 아래 내용 그대로 생성 — 기존 `environment` 필드에 `FASTAPI_URL`을 새로 추가하고 `ML_SERVER_URL` 값을 Cloud Map 주소로 바꾼 것 외에는 현재 배포된 태스크 정의와 동일):

```json
{
  "family": "solaraivle-backend",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "512",
  "memory": "1024",
  "executionRoleArn": "arn:aws:iam::251917136397:role/solaraivleEcsTaskExecutionRole",
  "taskRoleArn": "arn:aws:iam::251917136397:role/solaraivleTaskRole",
  "containerDefinitions": [
    {
      "name": "solaraivle-backend",
      "image": "251917136397.dkr.ecr.ap-northeast-2.amazonaws.com/solaraivle-backend:v2",
      "portMappings": [{ "containerPort": 8080, "protocol": "tcp" }],
      "essential": true,
      "environment": [
        { "name": "DB_URL", "value": "jdbc:mysql://solaraivle-db.c7ek60soyfx9.ap-northeast-2.rds.amazonaws.com:3306/solaraivle?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true" },
        { "name": "DB_USERNAME", "value": "admin" },
        { "name": "REDIS_HOST", "value": "solaraivle-redis.0pvsp8.0001.apn2.cache.amazonaws.com" },
        { "name": "REDIS_PORT", "value": "6379" },
        { "name": "AI_SERVER_URL", "value": "http://localhost:9000" },
        { "name": "VISION_AI_URL", "value": "http://localhost:8001/api/v1/vision/shade-analysis" },
        { "name": "DEMO_DATA_ENABLED", "value": "false" },
        { "name": "JPA_DDL_AUTO", "value": "update" },
        { "name": "FASTAPI_URL", "value": "http://vision-ai.solaraivle.internal:8001" },
        { "name": "ML_SERVER_URL", "value": "http://ranking-ml.solaraivle.internal:8002" }
      ],
      "secrets": [
        { "name": "DB_PASSWORD", "valueFrom": "arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:rds!db-66dc3722-d899-4e15-8191-d96b80fef766-LaTfwG:password::" },
        { "name": "JWT_SECRET", "valueFrom": "arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/jwt-secret-2oKy22" },
        { "name": "VWORLD_API_KEY", "valueFrom": "arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/vworld-api-key-XY6Et9" },
        { "name": "MAIL_USERNAME", "valueFrom": "arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/mail-username-PSMP8C" },
        { "name": "MAIL_PASSWORD", "valueFrom": "arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/mail-password-eOWckj" },
        { "name": "GOOGLE_CLIENT_ID", "valueFrom": "arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/google-client-id-4SJhRd" },
        { "name": "GOOGLE_CLIENT_SECRET", "valueFrom": "arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/google-client-secret-ziQXC7" },
        { "name": "OPENAI_API_KEY", "valueFrom": "arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/openai-api-key-MNBI0m" }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/solaraivle-backend",
          "awslogs-region": "ap-northeast-2",
          "awslogs-stream-prefix": "ecs"
        }
      }
    }
  ]
}
```

**주의:** `image` 태그(`v2`)가 실제로 ECR에 존재하는 최신 태그인지 먼저 확인한다:
```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
aws ecs describe-task-definition --task-definition solaraivle-backend --region ap-northeast-2 --query 'taskDefinition.containerDefinitions[0].image' --output text
```
위 명령 출력이 위 JSON의 `image` 값과 다르면, 그 출력값으로 JSON의 `image` 필드를 교체한 뒤 진행한다(현재 실행 중인 이미지를 그대로 유지하면서 환경변수만 바꾸기 위함).

- [ ] **Step 2: 새 태스크 정의 등록 + 서비스 업데이트**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
$region = "ap-northeast-2"
aws ecs register-task-definition --cli-input-json file://C:/Users/User/AppData/Local/Temp/claude/backend-task-def-updated.json --region $region --query 'taskDefinition.[family,revision]' --output text
aws ecs update-service --cluster solaraivle-cluster --service solaraivle-backend-svc --task-definition solaraivle-backend --region $region --query 'service.[status,taskDefinition]' --output text
aws ecs wait services-stable --cluster solaraivle-cluster --services solaraivle-backend-svc --region $region
```

- [ ] **Step 3: 엔드투엔드 검증 — 유휴부지 랭킹 조회**

실제 프론트(또는 `curl`)로 유휴부지 검색/랭킹 API를 호출해서 200 응답과 실제 랭킹 데이터가 오는지 확인. 502/504가 나면 CloudWatch 로그(`/ecs/solaraivle-backend`, `/ecs/solaraivle-ranking-ml`)에서 원인 확인.

- [ ] **Step 4: 엔드투엔드 검증 — 유휴부지 보고서 다운로드**

프론트에서 유휴부지 후보 하나를 골라 보고서 다운로드를 실행, PDF 안에 Vision AI 분석 결과(폴리곤 이미지 등)가 포함되는지 확인. 실패 시 `/ecs/solaraivle-vision-ai` 로그 확인.

---

### Task 7: 챗봇 — DB 시크릿 생성 + IAM 권한 추가

**Files:** 없음

**Interfaces:**
- Produces: Secrets Manager 시크릿 `solaraivle/chatbot-database-url`, `solaraivleEcsTaskExecutionRole`이 이 시크릿을 읽을 수 있는 권한

- [ ] **Step 1: RDS 비밀번호를 가져와 DATABASE_URL 조합 후 시크릿 생성 (화면에 값 노출 안 함)**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
$region = "ap-northeast-2"

$rdsSecretJson = aws secretsmanager get-secret-value --secret-id "arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:rds!db-66dc3722-d899-4e15-8191-d96b80fef766-LaTfwG" --region $region --query 'SecretString' --output text | ConvertFrom-Json
$dbPassword = $rdsSecretJson.password
$databaseUrl = "mysql+pymysql://admin:$dbPassword@solaraivle-db.c7ek60soyfx9.ap-northeast-2.rds.amazonaws.com:3306/solaraivle"

aws secretsmanager create-secret --name solaraivle/chatbot-database-url --secret-string $databaseUrl --region $region --query 'ARN' --output text
```

**주의:** 이 명령의 출력(ARN)은 화면에 표시되지만, `$databaseUrl`(비밀번호 포함)은 별도로 `Write-Output` 하지 않는다.

- [ ] **Step 2: 생성된 시크릿 ARN 확인**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
aws secretsmanager describe-secret --secret-id solaraivle/chatbot-database-url --region ap-northeast-2 --query 'ARN' --output text
```

이 ARN을 이후 태스크 정의(Task 9)에서 사용하도록 기록해둔다.

- [ ] **Step 3: `solaraivleEcsTaskExecutionRole`의 시크릿 읽기 정책에 새 ARN 추가**

기존 인라인 정책(`solaraivle-secrets-read`)에 새 시크릿 ARN을 추가한 전체 정책을 다시 작성해서 덮어쓴다:

`C:\Users\User\AppData\Local\Temp\claude\ecs-secrets-policy-v2.json`:
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
        "<Step 2에서 확인한 solaraivle/chatbot-database-url의 실제 ARN으로 교체>"
      ]
    }
  ]
}
```

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
aws iam put-role-policy --role-name solaraivleEcsTaskExecutionRole --policy-name solaraivle-secrets-read --policy-document file://C:/Users/User/AppData/Local/Temp/claude/ecs-secrets-policy-v2.json --region ap-northeast-2
```

**Step 3 검증:** 에러 없이 끝나면 성공(이 명령은 출력이 없음). `aws iam get-role-policy --role-name solaraivleEcsTaskExecutionRole --policy-name solaraivle-secrets-read`로 9개 ARN이 다 들어있는지 재확인 가능.

---

### Task 8: 챗봇 — Dockerfile 작성 + 이미지 빌드/검증 + ECR push

**Files:**
- Create: `chat_bot/Dockerfile`
- Create: `chat_bot/.dockerignore`

**Interfaces:**
- Consumes: 없음
- Produces: ECR 이미지 `251917136397.dkr.ecr.ap-northeast-2.amazonaws.com/solaraivle-chatbot:v1`, 컨테이너는 포트 8010에서 `GET /health`, `POST /chat`, `POST /chat/pdf` 제공

- [ ] **Step 1: 브랜치 생성**

```bash
cd "C:\Users\User\Desktop\ai-repos-check\chat_bot"
git checkout main
git pull
git checkout -b infra/ecs-deploy
```

- [ ] **Step 2: Dockerfile 작성**

`chat_bot/Dockerfile`:
```dockerfile
FROM python:3.11-slim
WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app app

EXPOSE 8010
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8010"]
```

- [ ] **Step 3: .dockerignore 작성**

`chat_bot/.dockerignore`:
```
.git/
__pycache__/
*.pyc
.venv/
.env
```

- [ ] **Step 4: 로컬 빌드 검증**

```bash
cd "C:\Users\User\Desktop\ai-repos-check\chat_bot"
docker build -t solaraivle-chatbot:v1 .
```

Expected: `Successfully tagged solaraivle-chatbot:v1`.

- [ ] **Step 5: 로컬 컨테이너 실행으로 기동 확인**

로컬 MySQL이 없어도 컨테이너 자체는 뜨는지만 확인(DB 연결 실패는 이 단계에서 정상):

```bash
docker run -d --name chatbot-test -p 8010:8010 -e OPENAI_API_KEY=dummy solaraivle-chatbot:v1
sleep 8
docker logs chatbot-test 2>&1 | tail -30
curl -s http://localhost:8010/health
docker rm -f chatbot-test
```

Expected: `curl`이 JSON 응답을 반환(DB 연결 자체가 실패해도 `/health`는 앱이 뜨기만 하면 응답해야 함 — 만약 앱이 기동 시점에 DB 연결을 강제로 시도해서 죽는다면 로그에서 확인하고, 실제 배포 시에는 진짜 RDS가 있으니 문제없음을 인지하고 넘어감).

- [ ] **Step 6: ECR 리포지토리 생성 + push**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
aws ecr create-repository --repository-name solaraivle-chatbot --image-scanning-configuration scanOnPush=true --region ap-northeast-2
```

```bash
export PATH="$PATH:/c/Program Files/Amazon/AWSCLIV2"
aws ecr get-login-password --region ap-northeast-2 | docker login --username AWS --password-stdin 251917136397.dkr.ecr.ap-northeast-2.amazonaws.com
docker tag solaraivle-chatbot:v1 251917136397.dkr.ecr.ap-northeast-2.amazonaws.com/solaraivle-chatbot:v1
docker push 251917136397.dkr.ecr.ap-northeast-2.amazonaws.com/solaraivle-chatbot:v1
```

- [ ] **Step 7: 커밋**

```bash
cd "C:\Users\User\Desktop\ai-repos-check\chat_bot"
git add Dockerfile .dockerignore
git commit -m "feat: ECS 배포용 Dockerfile 추가"
git push -u origin infra/ecs-deploy
```

---

### Task 9: 챗봇 — ECS 태스크 정의 + 서비스 + ALB 라우팅

**Files:** 없음

**Interfaces:**
- Consumes: Task 1의 `$sgChatbot`; Task 7의 `solaraivle/chatbot-database-url` ARN; Task 8의 ECR 이미지
- Produces: `https://d1iuhepb03p42r.cloudfront.net/chat`, `/chat/pdf`가 챗봇 서비스로 라우팅됨

- [ ] **Step 1: 로그 그룹 생성**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
aws logs create-log-group --log-group-name /ecs/solaraivle-chatbot --region ap-northeast-2
```

- [ ] **Step 2: 태스크 정의 작성 및 등록**

`C:\Users\User\AppData\Local\Temp\claude\chatbot-task-def.json` (`<...>`는 Task 7 Step 2에서 확인한 실제 ARN으로 교체):
```json
{
  "family": "solaraivle-chatbot",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "512",
  "memory": "1024",
  "executionRoleArn": "arn:aws:iam::251917136397:role/solaraivleEcsTaskExecutionRole",
  "taskRoleArn": "arn:aws:iam::251917136397:role/solaraivleTaskRole",
  "containerDefinitions": [
    {
      "name": "chatbot",
      "image": "251917136397.dkr.ecr.ap-northeast-2.amazonaws.com/solaraivle-chatbot:v1",
      "portMappings": [{ "containerPort": 8010, "protocol": "tcp" }],
      "essential": true,
      "environment": [
        { "name": "CORS_ORIGINS", "value": "http://localhost:5173,https://main.d2bi30avd3chif.amplifyapp.com,https://infra-amplify-deploy.d2bi30avd3chif.amplifyapp.com" },
        { "name": "PORT", "value": "8010" }
      ],
      "secrets": [
        { "name": "DATABASE_URL", "valueFrom": "<Task 7의 solaraivle/chatbot-database-url ARN>" },
        { "name": "JWT_SECRET", "valueFrom": "arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/jwt-secret-2oKy22" },
        { "name": "OPENAI_API_KEY", "valueFrom": "arn:aws:secretsmanager:ap-northeast-2:251917136397:secret:solaraivle/openai-api-key-MNBI0m" }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/solaraivle-chatbot",
          "awslogs-region": "ap-northeast-2",
          "awslogs-stream-prefix": "ecs"
        }
      }
    }
  ]
}
```

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
aws ecs register-task-definition --cli-input-json file://C:/Users/User/AppData/Local/Temp/claude/chatbot-task-def.json --region ap-northeast-2 --query 'taskDefinition.[family,revision]' --output text
```

- [ ] **Step 3: 타깃그룹 생성**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
$region = "ap-northeast-2"
$vpcId = "vpc-04f1b96d8fd3919f1"

$tgChatbot = (aws elbv2 create-target-group `
  --name solaraivle-chatbot-tg `
  --protocol HTTP --port 8010 `
  --vpc-id $vpcId `
  --target-type ip `
  --health-check-path /health `
  --health-check-interval-seconds 30 `
  --health-check-timeout-seconds 5 `
  --healthy-threshold-count 2 `
  --unhealthy-threshold-count 3 `
  --region $region --query 'TargetGroups[0].TargetGroupArn' --output text)
Write-Output "TG_CHATBOT=$tgChatbot"
```

- [ ] **Step 4: 리스너 규칙 추가 (경로 기반 라우팅)**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
$region = "ap-northeast-2"
$listenerArn = "arn:aws:elasticloadbalancing:ap-northeast-2:251917136397:listener/app/solaraivle-alb/782974e6c4737c8b/47199baf3ecec9ba"
$tgChatbot = "<Step 3의 TG_CHATBOT 값>"

aws elbv2 create-rule `
  --listener-arn $listenerArn `
  --priority 10 `
  --conditions Field=path-pattern,Values='/chat*' `
  --actions Type=forward,TargetGroupArn=$tgChatbot `
  --region $region
```

- [ ] **Step 5: ECS 서비스 생성**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
$region = "ap-northeast-2"
$pub1 = "subnet-03913caae6189e6db"
$pub2 = "subnet-0f679a21dca2d3374"
$sgChatbot = "<Task 1의 SG_CHATBOT 값>"
$tgChatbot = "<Step 3의 TG_CHATBOT 값>"

aws ecs create-service `
  --cluster solaraivle-cluster `
  --service-name solaraivle-chatbot-svc `
  --task-definition solaraivle-chatbot `
  --desired-count 1 `
  --launch-type FARGATE `
  --health-check-grace-period-seconds 90 `
  --network-configuration "awsvpcConfiguration={subnets=[$pub1,$pub2],securityGroups=[$sgChatbot],assignPublicIp=ENABLED}" `
  --load-balancers "targetGroupArn=$tgChatbot,containerName=chatbot,containerPort=8010" `
  --region $region
```

(`health-check-grace-period-seconds`는 이전 백엔드 배포에서 기동 지연 때문에 태스크가 죽었던 문제를 겪었으므로 처음부터 넣는다.)

- [ ] **Step 6: 안정화 대기 + 타깃 헬스 확인**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
$region = "ap-northeast-2"
aws ecs wait services-stable --cluster solaraivle-cluster --services solaraivle-chatbot-svc --region $region
$tgChatbot = "<Step 3의 TG_CHATBOT 값>"
aws elbv2 describe-target-health --target-group-arn $tgChatbot --region $region --query 'TargetHealthDescriptions[0].TargetHealth' --output json
```

Expected: `"State": "healthy"`.

- [ ] **Step 7: 외부 라우팅 검증**

```bash
curl -s -i -X POST https://d1iuhepb03p42r.cloudfront.net/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"안녕"}'
```

Expected: `200`과 함께 `{"success":true,"message":"답변을 생성했습니다.","data":{...}}` 형태 응답. 실패 시 `/ecs/solaraivle-chatbot` 로그와 타깃그룹 헬스 상태 확인.

---

### Task 10: 프론트엔드 — 챗봇 API 연동 수정

**Files:**
- Modify: `frontEnd/src/api/chatApi.js`
- Modify: `frontEnd/src/components/ChatBot.jsx` (엑셀 → PDF 업로드로 바뀐 부분 반영)

**Interfaces:**
- Consumes: Task 9의 `https://d1iuhepb03p42r.cloudfront.net/chat`, `/chat/pdf`
- Produces: `sendChatMessage(message)`, `sendChatPdf(file, message)` — 챗봇 서버를 직접 호출

- [ ] **Step 1: 브랜치 생성**

```bash
cd "C:\Users\User\Desktop\빅프로젝트 프론트\frontEnd"
git checkout main
git pull
git checkout -b infra/ai-services-deploy
```

- [ ] **Step 2: `chatApi.js`를 챗봇 전용 axios 인스턴스로 교체**

`src/api/chatApi.js` 전체를 다음으로 교체:
```javascript
import axios from 'axios';

const chatbotInstance = axios.create({
  baseURL: import.meta.env.VITE_CHATBOT_BASE_URL || 'http://localhost:8010',
});

export const sendChatMessage = async (message) => {
  const { data } = await chatbotInstance.post('/chat', { message });
  return data;
};

export const sendChatPdf = async (file, message) => {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('message', message);

  const { data } = await chatbotInstance.post('/chat/pdf', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return data;
};
```

- [ ] **Step 3: `sendChatExcel`을 쓰던 컴포넌트를 `sendChatPdf`로 갱신**

```bash
grep -rn "sendChatExcel" "C:\Users\User\Desktop\빅프로젝트 프론트\frontEnd\src"
```

나오는 곳(예: `ChatBot.jsx`)에서 `sendChatExcel` → `sendChatPdf`로 함수명을 바꾸고, 호출부에 `message` 인자를 같이 넘기도록 수정한다(기존에 `file`만 넘겼다면 사용자가 입력한 채팅 메시지를 같이 전달하도록 조정). 파일 선택 `<input type="file" accept=...>`이 있다면 `accept=".pdf"` 또는 `application/pdf`로 맞춘다.

- [ ] **Step 4: 로컬 빌드 검증**

```bash
cd "C:\Users\User\Desktop\빅프로젝트 프론트\frontEnd"
npm run build
```

Expected: 에러 없이 빌드 성공.

- [ ] **Step 5: Amplify 환경변수에 `VITE_CHATBOT_BASE_URL` 추가**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
aws amplify update-app --app-id d2bi30avd3chif --region ap-northeast-2 `
  --environment-variables VITE_API_BASE_URL=https://d1iuhepb03p42r.cloudfront.net/api,VITE_GOOGLE_CLIENT_ID=1037939180659-dbatbibjsflh7vqh3erf1a8sv31rgdjh.apps.googleusercontent.com,VITE_CHATBOT_BASE_URL=https://d1iuhepb03p42r.cloudfront.net `
  --query 'app.environmentVariables' --output json
```

- [ ] **Step 6: 커밋 + push**

```bash
cd "C:\Users\User\Desktop\빅프로젝트 프론트\frontEnd"
git add src/api/chatApi.js src/components/ChatBot.jsx
git commit -m "feat: 챗봇 서버 직접 연동으로 변경"
git push -u origin infra/ai-services-deploy
```

이 브랜치는 아직 `main`에 연결된 Amplify 프로덕션 브랜치가 아니므로 자동 배포되지 않는다 — 실제 화면 검증은 Task 11에서 별도 임시 브랜치 연결로 진행한다.

- [ ] **Step 7: Amplify에 이 브랜치 임시 연결해서 미리보기 배포**

```powershell
$env:Path += ";C:\Program Files\Amazon\AWSCLIV2"
aws amplify create-branch --app-id d2bi30avd3chif --branch-name infra/ai-services-deploy --region ap-northeast-2
aws amplify start-job --app-id d2bi30avd3chif --branch-name infra/ai-services-deploy --job-type RELEASE --region ap-northeast-2 --query 'jobSummary.[jobId,status]' --output text
```

빌드가 끝나면 `https://infra-ai-services-deploy.d2bi30avd3chif.amplifyapp.com`에서 확인 가능.

---

### Task 11: 전체 엔드투엔드 검증 + PR 생성

**Files:** 없음

- [ ] **Step 1: 챗봇 실제 대화 테스트**

`https://infra-ai-services-deploy.d2bi30avd3chif.amplifyapp.com`에서 챗봇 아이콘을 열고 실제 메시지를 보내서 응답이 오는지 확인. 브라우저 개발자도구 콘솔에 CORS 에러가 없는지 확인.

- [ ] **Step 2: 로그인 연동 채팅 기록 확인**

로그인 상태에서 챗봇과 대화 후, 새로고침해도 최근 대화가 이어지는지 확인(JWT_SECRET이 백엔드와 일치해야 가능 — 안 되면 두 시크릿 값이 진짜 같은지 재확인).

- [ ] **Step 3: 유휴부지 랭킹/보고서 기능 재확인**

Task 6에서 이미 확인했지만, 프론트 화면에서 실제로 유휴부지 검색 → 랭킹 결과 → 보고서 다운로드까지 한 번에 이어서 테스트.

- [ ] **Step 4: PR 생성 (머지는 하지 않음)**

```bash
cd "C:\Users\User\Desktop\ai-repos-check\visionAI"
gh pr create --base main --head infra/ecs-deploy --title "AWS ECS 배포: Dockerfile 추가" --body "Vision AI를 ECS Fargate에 배포하기 위한 Dockerfile 추가. 실제 배포 후 유휴부지 보고서 생성 기능으로 엔드투엔드 검증 완료."

cd "C:\Users\User\Desktop\ai-repos-check\Ranking_ML"
gh pr create --base main --head infra/ecs-deploy --title "AWS ECS 배포: Dockerfile 추가" --body "Ranking ML을 ECS Fargate에 배포하기 위한 Dockerfile 추가. 실제 배포 후 유휴부지 랭킹 조회 기능으로 엔드투엔드 검증 완료."

cd "C:\Users\User\Desktop\ai-repos-check\chat_bot"
gh pr create --base main --head infra/ecs-deploy --title "AWS ECS 배포: Dockerfile 추가" --body "챗봇 서버를 ECS Fargate에 배포하기 위한 Dockerfile 추가. 실제 배포 후 프론트 챗봇 UI로 엔드투엔드 검증 완료."

cd "C:\Users\User\Desktop\빅프로젝트 프론트\frontEnd"
gh pr create --base main --head infra/ai-services-deploy --title "챗봇 서버 직접 연동" --body "chatApi.js가 백엔드 대신 새로 배포된 챗봇 서버(solaraivle-chatbot)를 직접 호출하도록 변경. /chat/excel -> /chat/pdf로 실제 라우트에 맞춤."

cd "C:\Users\User\Desktop\빅프로젝트"
git checkout infra/ai-services-deploy
git push -u origin infra/ai-services-deploy
gh pr create --base main --head infra/ai-services-deploy --title "AI 서비스 3종 배포 설계 문서" --body "Vision AI/Ranking ML/챗봇 배포 설계 문서. 코드 변경은 없음(env var는 ECS 태스크 정의에서 직접 관리)."
```

**Step 4 검증:** 5개 저장소 모두 PR URL이 출력되는지 확인. 머지는 사용자가 팀 리뷰 후 별도로 진행.
