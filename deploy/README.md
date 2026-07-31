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

## 알아둘 점

- **NAT Gateway 없음** — Fargate 태스크와 ALB 모두 퍼블릭 서브넷에 위치, 비용 절감 목적. 인터넷 노출은 보안그룹으로만 제어됨(태스크는 ALB의 8080 포트만 허용).
- **HTTPS 없음** — 현재 HTTP:80만 리스너로 열려있음. 도메인/ACM 인증서 준비되면 HTTPS 추가 필요.
- **헬스체크 그레이스 기간 150초로 설정** — 이 앱이 Fargate(0.5 vCPU)에서 기동에 약 70초 걸려서, 그레이스 기간 없이는 ALB가 기동 중인 태스크를 unhealthy로 판단해 죽여버리는 문제가 있었음(최초 배포 시 실제로 발생, 그레이스 기간 추가로 해결).
- **AI 서버 연동 미배포** — `AI_SERVER_URL`, `ML_SERVER_URL`, `VISION_AI_URL`은 전부 `localhost` 기본값 placeholder. 이번 배포 범위가 아니므로 해당 기능 호출 시에는 실패하지만 앱 기동 자체에는 영향 없음(런타임 호출 시점에만 실패).
- **DB_USERNAME=admin** — RDS 마스터 사용자. 프로덕션이라면 애플리케이션 전용 최소권한 사용자를 별도로 만드는 게 좋음(지금은 검증 단계라 마스터 계정 그대로 사용).

## 검증 결과

```
curl http://solaraivle-alb-1327052553.ap-northeast-2.elb.amazonaws.com/api/actuator/health
→ {"groups":["liveness","readiness"],"status":"UP"}

curl http://solaraivle-alb-1327052553.ap-northeast-2.elb.amazonaws.com/api/boards
→ {"data":{"content":[],...},"message":"게시글 목록 조회 성공","success":true}
```

DB(RDS), Redis(ElastiCache) 연결 모두 정상 확인됨.
