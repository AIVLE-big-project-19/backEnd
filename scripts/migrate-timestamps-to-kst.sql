-- PR #49(컨테이너 시간대 Asia/Seoul 고정) 배포 직전에 딱 한 번만 실행할 것.
-- 기존에 UTC로 잘못 저장된 created_at/updated_at을 +9시간 보정한다.
--
-- created_at <= UTC_TIMESTAMP() 조건 덕분에, 배포 후 정상적으로 KST로 찍힌
-- 새 데이터(UTC보다 미래 시각)는 이 조건에 안 걸려서 실수로 다시 돌려도 안전하다.
-- 단, 배포 전에 여러 번 실행하면 매번 +9시간씩 누적되니 정확히 한 번만 실행할 것.

UPDATE analysis_snapshot
SET created_at = DATE_ADD(created_at, INTERVAL 9 HOUR),
    updated_at = DATE_ADD(updated_at, INTERVAL 9 HOUR)
WHERE created_at <= UTC_TIMESTAMP();

UPDATE board
SET created_at = DATE_ADD(created_at, INTERVAL 9 HOUR),
    updated_at = DATE_ADD(updated_at, INTERVAL 9 HOUR)
WHERE created_at <= UTC_TIMESTAMP();

UPDATE comment
SET created_at = DATE_ADD(created_at, INTERVAL 9 HOUR),
    updated_at = DATE_ADD(updated_at, INTERVAL 9 HOUR)
WHERE created_at <= UTC_TIMESTAMP();

UPDATE site_analysis
SET created_at = DATE_ADD(created_at, INTERVAL 9 HOUR),
    updated_at = DATE_ADD(updated_at, INTERVAL 9 HOUR)
WHERE created_at <= UTC_TIMESTAMP();

UPDATE idle_land
SET created_at = DATE_ADD(created_at, INTERVAL 9 HOUR),
    updated_at = DATE_ADD(updated_at, INTERVAL 9 HOUR)
WHERE created_at <= UTC_TIMESTAMP();

UPDATE notification
SET created_at = DATE_ADD(created_at, INTERVAL 9 HOUR),
    updated_at = DATE_ADD(updated_at, INTERVAL 9 HOUR)
WHERE created_at <= UTC_TIMESTAMP();

UPDATE refresh_tokens
SET created_at = DATE_ADD(created_at, INTERVAL 9 HOUR),
    updated_at = DATE_ADD(updated_at, INTERVAL 9 HOUR)
WHERE created_at <= UTC_TIMESTAMP();

UPDATE users
SET created_at = DATE_ADD(created_at, INTERVAL 9 HOUR),
    updated_at = DATE_ADD(updated_at, INTERVAL 9 HOUR)
WHERE created_at <= UTC_TIMESTAMP();

UPDATE user_consents
SET created_at = DATE_ADD(created_at, INTERVAL 9 HOUR),
    updated_at = DATE_ADD(updated_at, INTERVAL 9 HOUR)
WHERE created_at <= UTC_TIMESTAMP();
