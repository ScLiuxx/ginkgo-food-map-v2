-- 举报第一版只覆盖点评和回复；被举报内容保持公开，由管理员核查后永久删除或驳回。
-- 提交时保存正文快照，作者在核查前自行删除后，管理员仍可按RISK-024继续核查。
CREATE TABLE content_reports (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '举报ID',
    reporter_user_id BIGINT UNSIGNED NOT NULL COMMENT '举报人逻辑ID',
    target_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'REVIEW点评或REPLY回复',
    target_id BIGINT UNSIGNED NOT NULL COMMENT '被举报内容逻辑ID',
    target_author_user_id BIGINT UNSIGNED NOT NULL COMMENT '举报时内容作者快照',
    review_id BIGINT UNSIGNED NOT NULL COMMENT '点评ID；举报回复时为父点评ID',
    content_snapshot VARCHAR(500) NOT NULL COMMENT '举报时正文快照，仅管理员可读',
    reason VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '举报原因代码',
    detail VARCHAR(200) NULL COMMENT '选填文字说明',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING待处理、RESOLVED已删除内容、DISMISSED未认定违规',
    handled_by BIGINT UNSIGNED NULL COMMENT '处理管理员逻辑ID',
    handled_at TIMESTAMP(6) NULL COMMENT '首次处理时间',
    decision_note VARCHAR(200) NULL COMMENT '处理备注',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '提交时间',
    PRIMARY KEY (id),
    -- RISK-017：同一用户对同一内容只有一次举报资格，处理完成后也不能重复提交。
    UNIQUE KEY uk_content_report_reporter_target (reporter_user_id, target_type, target_id),
    -- 后台按状态和处理时间取待办，避免全表扫描。
    KEY idx_content_report_queue (status, created_at, id),
    -- RISK-026：同一内容多名用户举报时按目标合并查看。
    KEY idx_content_report_target (target_type, target_id, created_at, id),
    CONSTRAINT chk_content_report_type CHECK (CAST(target_type AS BINARY) IN ('REVIEW','REPLY')),
    CONSTRAINT chk_content_report_reason CHECK (CAST(reason AS BINARY) IN
        ('SPAM','ABUSE','PORNOGRAPHY','ILLEGAL','PRIVACY','IRRELEVANT','OTHER')),
    CONSTRAINT chk_content_report_status CHECK (CAST(status AS BINARY) IN
        ('PENDING','RESOLVED','DISMISSED')),
    CONSTRAINT chk_content_report_ids CHECK (
        reporter_user_id BETWEEN 1 AND 9223372036854775807
        AND target_id BETWEEN 1 AND 9223372036854775807
        AND target_author_user_id BETWEEN 1 AND 9223372036854775807
        AND review_id BETWEEN 1 AND 9223372036854775807
        AND (handled_by IS NULL OR handled_by BETWEEN 1 AND 9223372036854775807)),
    -- 举报点评时父点评就是目标本身；回复ID与父点评ID可能数值相同，不能据此比较。
    CONSTRAINT chk_content_report_target CHECK (
        CAST(target_type AS BINARY)='REPLY' OR review_id=target_id),
    CONSTRAINT chk_content_report_snapshot CHECK (
        CHAR_LENGTH(content_snapshot) BETWEEN 1 AND 500
        AND NOT REGEXP_LIKE(content_snapshot, '^[[:space:]]|[[:space:]]$')),
    CONSTRAINT chk_content_report_detail CHECK (
        detail IS NULL OR (CHAR_LENGTH(detail) BETWEEN 1 AND 200
            AND NOT REGEXP_LIKE(detail, '^[[:space:]]|[[:space:]]$'))),
    -- 待处理不能带处理字段；终态必须记录处理人和时间，驳回说明保持选填。
    CONSTRAINT chk_content_report_handle CHECK (
        (CAST(status AS BINARY)='PENDING'
            AND handled_by IS NULL AND handled_at IS NULL AND decision_note IS NULL)
        OR (CAST(status AS BINARY) IN ('RESOLVED','DISMISSED')
            AND handled_by IS NOT NULL AND handled_at IS NOT NULL)),
    CONSTRAINT chk_content_report_time CHECK (handled_at IS NULL OR handled_at >= created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
    COMMENT='点评与回复举报，内容快照仅管理员可读';
