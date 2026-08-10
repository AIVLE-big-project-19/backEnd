# ECS 배포 산출물 (ap-northeast-2)

이 브랜치(`infra/ecs-deploy`)에서 수동으로 생성한 AWS 리소스 기록. `docs/superpowers/plans/`에 별도 계획 문서는 없고, 이 README가 실제 생성된 리소스 ID를 남기는 기록임.

## 생성된 리소스

- **VPC**: `vpc-04f1b96d8fd3919f1` (10.0.0.0/16)
  - 퍼블릭 서브넷: `subnet-03913caae6189e6db`(2a), `subnet-0f679a21dca2d3374`(2c)
  - 프라이빗 서브넷: `subnet-06c73e0c6e68be8ec`(2a), `subnet-0388e3c512403d759`(2c)
  - IGW: `igw-0296ad139657e740a`, 퍼블릭 라우트테이블: `rtb-023cebd2aede2419f`
- **보안그룹**: `solaraivle-alb-sg`(80 from 0.0.0.0/0), `solaraivle-ecs-task-sg`(8080 from alb-sg), `solaraivle-rds-sg`(3306 from task-sg), `solaraivle-redis-sg`(6379 from task-sg)
- **RDS**: `solaraivle-db` (MySQL 8.0.42, db.t3.micro), 엔드포인트 `solaraivle-db.c7ek60soyfx9.ap-northeast-2.rds.amazonaws.com:3306`, 마스터 암호는 RDS 관리형 Secrets Manager 시크릿(`rds!db-66dc3722-...`)
- **ElastiCache**: `solaraivle-redis` (Redis 7.1, cache.t3.micro), 엔드포인트 `solaraivle-redis.0pvsp8.0001.apn2.cache.amazonaws.com:6379`
- **ECR**: `251917136397.dkr.ecr.ap-northeast-2.amazonaws.com/solaraivle-backend`
- **Secrets Manager**: `solaraivle/jwt-secret`, `solaraivle/vworld-api-key`, `solaraivle/mail-username`, `solaraivle/mail-password`, `solaraivle/google-client-id`, `solaraivle/google-client-secret`, `solaraivle/openai-api-key`
- **IAM**: `solaraivleEcsTaskExecutionRole`(ECR pull + CloudWatch Logs + 위 시크릿들 읽기), `solaraivleTaskRole`(권한 없음, placeholder)
- **ECS**: 클러스터 `solaraivle-cluster`, 태스크 정의 `solaraivle-backend`(이 폴더의 `ecs-task-def.json`), 서비스 `solaraivle-backend-svc` (desired count 1, `healthCheckGracePeriodSeconds: 150`)
- **ALB**: `solaraivle-alb` → `solaraivle-alb-1327052553.ap-northeast-2.elb.amazonaws.com` (HTTP:80) → 타깃그룹 `solaraivle-tg` (헬스체크 `/api/actuator/health`)
- **CloudFront**: 배포 ID `E23LXWG7OGAIRR` → `https://d1iuhepb03p42r.cloudfront.net` — ALB(HTTP)를 오리진으로 두고 CloudFront가 HTTPS 종단 처리. 커스텀 도메인 없이 AWS 관리형 인증서로 무료 HTTPS 제공. 캐싱은 `CachingDisabled` 정책으로 꺼둠(API라 캐싱 의미 없음), 모든 메서드(GET/POST/PUT/PATCH/DELETE)와 헤더/쿠키/쿼리스트링은 `AllViewer` 오리진 요청 정책으로 그대로 오리진에 전달.

## 알아둘 점

- **NAT Gateway 없음** — Fargate 태스크와 ALB 모두 퍼블릭 서브넷에 위치, 비용 절감 목적. 인터넷 노출은 보안그룹으로만 제어됨(태스크는 ALB의 8080 포트만 허용).
- **HTTPS는 CloudFront에서만 제공** — ALB 자체는 여전히 HTTP:80만 리스닝. 실제 사용자는 CloudFront 주소(`https://d1iuhepb03p42r.cloudfront.net`)로 접속해야 HTTPS 적용됨. ALB로 직접 접속하면 HTTP만 됨. 커스텀 도메인이 생기면 CloudFront에 Alternate Domain Name + ACM 인증서만 추가하면 됨(재구축 불필요).
- **헬스체크 그레이스 기간 150초로 설정** — 이 앱이 Fargate(0.5 vCPU)에서 기동에 약 70초 걸려서, 그레이스 기간 없이는 ALB가 기동 중인 태스크를 unhealthy로 판단해 죽여버리는 문제가 있었음(최초 배포 시 실제로 발생, 그레이스 기간 추가로 해결).
- **AI 서버 연동 상태** — `vision-ai`, `ranking-ml`, `chatbot`, `policy-agent` 4개 서비스는 모두 `solaraivle-cluster`에 내부 전용 ECS 서비스로 배포되어 Cloud Map(`solaraivle.internal`)으로 연결됨. `AI_SERVER_URL`만 여전히 `localhost` placeholder로 남아 있음(별도 작업 필요).
- **DB_USERNAME=admin** — RDS 마스터 사용자. 프로덕션이라면 애플리케이션 전용 최소권한 사용자를 별도로 만드는 게 좋음(지금은 검증 단계라 마스터 계정 그대로 사용).

## 검증 결과

```
curl http://solaraivle-alb-1327052553.ap-northeast-2.elb.amazonaws.com/api/actuator/health
→ {"groups":["liveness","readiness"],"status":"UP"}

curl http://solaraivle-alb-1327052553.ap-northeast-2.elb.amazonaws.com/api/boards
→ {"data":{"content":[],...},"message":"게시글 목록 조회 성공","success":true}

curl https://d1iuhepb03p42r.cloudfront.net/api/actuator/health
→ {"groups":["liveness","readiness"],"status":"UP"}  (HTTPS, CloudFront 경유)

curl https://d1iuhepb03p42r.cloudfront.net/api/boards
→ {"data":{"content":[],...},"message":"게시글 목록 조회 성공","success":true}  (HTTPS, CloudFront 경유)
```

DB(RDS), Redis(ElastiCache), HTTPS(CloudFront) 모두 정상 확인됨.

## 프론트엔드에 전달할 API 주소

```
https://d1iuhepb03p42r.cloudfront.net/api
```

## CI/CD (GitHub Actions)

`.github/workflows/deploy.yml` — `main` 브랜치에 push(또는 수동 실행)되면 자동으로:
1. Docker 이미지 빌드 (`git sha`로 태깅)
2. ECR push
3. 현재 ECS 태스크 정의를 내려받아 이미지만 새 걸로 교체한 새 리비전 등록
4. `solaraivle-backend-svc` 서비스를 새 리비전으로 업데이트, 안정화될 때까지 대기

**인증 방식**: 장기 액세스 키를 GitHub Secrets에 저장하지 않고, **OIDC(OpenID Connect)**로 GitHub Actions가 임시 자격증명을 발급받도록 구성함.

- IAM OIDC 프로바이더: `token.actions.githubusercontent.com`
- IAM 역할: `solaraivle-github-actions-deploy` — `repo:AIVLE-big-project-19/backEnd:*`에서 오는 요청만 신뢰(trust policy에 `sub` 조건으로 이 저장소로 제한), 권한은 ECR push + ECS 태스크정의 등록/서비스 업데이트 + 관련 IAM 역할 2개에 대한 `PassRole`만 부여(최소권한)
- **GitHub Secrets에 아무것도 등록할 필요 없음** — 워크플로우가 실행될 때마다 AWS STS에서 15분짜리 임시 토큰을 발급받는 방식이라 유출 위험이 있는 정적 키가 아예 없음

**주의**: 워크플로우 트리거가 `main` push라서, `main`에 머지되는 순간 실제 운영 중인 ECS 서비스가 바로 업데이트됨. 인프라를 지운 상태에서 `main`에 push하면 `ecs describe-task-definition`/`ecs update-service` 호출이 실패하며 워크플로우가 실패함(인프라가 있을 때만 정상 동작).
