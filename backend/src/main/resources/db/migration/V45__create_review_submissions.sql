-- 待发布内容归review模块；申请、账号和文件只保存逻辑ID，不跨模块建立外键或触发器。
-- 仅建立存储结构，不自动创建点评、不改变既有BOOLEAN推荐字段或评分统计。
CREATE TABLE review_submissions (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '待发布点评ID',
    restaurant_request_id BIGINT UNSIGNED NOT NULL COMMENT '当前关联的新店申请逻辑ID，一申请最多一条',
    origin_request_id BIGINT UNSIGNED NOT NULL COMMENT '首次关联的新店申请逻辑ID，驳回重提后保留',
    author_user_id BIGINT UNSIGNED NOT NULL COMMENT '原点评作者逻辑ID，不使用审核管理员身份',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'WAITING'
        COMMENT 'WAITING待发布、PUBLISHED已发布、WITHDRAWN已撤回',
    taste_score DECIMAL(3,1) NULL COMMENT '味道0至10，步进0.5；发布转移后清空',
    value_score DECIMAL(3,1) NULL COMMENT '性价比0至10，步进0.5；发布转移后清空',
    service_score DECIMAL(3,1) NULL COMMENT '服务0至10，步进0.5；发布转移后清空',
    ambience_score DECIMAL(3,1) NULL COMMENT '环境0至10，步进0.5；发布转移后清空',
    recommendation VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'RECOMMENDED推荐、NEUTRAL一般、NOT_RECOMMENDED不推荐；无默认态度，发布后清空',
    body VARCHAR(500) NULL COMMENT '去首尾空白的5至500码点正文；发布转移后清空',
    client_request_id BINARY(16) NOT NULL COMMENT '作者本次提交UUID，网络重试沿用',
    request_hash BINARY(32) NOT NULL COMMENT '首次规范化提交内容SHA256，不随待审编辑改变',
    content_hash BINARY(32) NOT NULL COMMENT '当前规范化内容及有序附图SHA256，编辑时更新',
    publish_request_id BINARY(16) NOT NULL COMMENT '服务端预留的正式发布UUID，修改和重试保持不变',
    published_review_id BIGINT UNSIGNED NULL COMMENT '正式点评逻辑ID，点评删除后仍保留防重回执',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    published_at TIMESTAMP(6) NULL COMMENT '正式发布时间，仅PUBLISHED有值',
    withdrawn_at TIMESTAMP(6) NULL COMMENT '撤回时间，仅WITHDRAWN有值',
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_submission_request (restaurant_request_id),
    UNIQUE KEY uk_review_submission_author_client (author_user_id, client_request_id),
    UNIQUE KEY uk_review_submission_publish (publish_request_id),
    UNIQUE KEY uk_review_submission_published_review (published_review_id),
    KEY idx_review_submission_author_status_time (author_user_id, status, created_at, id),
    KEY idx_review_submission_author_time (author_user_id, created_at, id),
    KEY idx_review_submission_origin (origin_request_id),
    CONSTRAINT chk_review_submission_ids CHECK (
        restaurant_request_id BETWEEN 1 AND 9223372036854775807
        AND origin_request_id BETWEEN 1 AND 9223372036854775807
        AND author_user_id BETWEEN 1 AND 9223372036854775807),
    CONSTRAINT chk_review_submission_status CHECK (
        status IN ('WAITING', 'PUBLISHED', 'WITHDRAWN')
        AND NOT REGEXP_LIKE(status, '[[:space:]]')),
    -- 显式检查NULL，防止MySQL CHECK的UNKNOWN语义放行不完整待发布内容。
    CONSTRAINT chk_review_submission_content CHECK (
        (status IN ('WAITING', 'WITHDRAWN')
            AND taste_score IS NOT NULL AND value_score IS NOT NULL
            AND service_score IS NOT NULL AND ambience_score IS NOT NULL
            AND recommendation IS NOT NULL AND body IS NOT NULL
            AND taste_score BETWEEN 0 AND 10 AND MOD(taste_score, 0.5) = 0
            AND value_score BETWEEN 0 AND 10 AND MOD(value_score, 0.5) = 0
            AND service_score BETWEEN 0 AND 10 AND MOD(service_score, 0.5) = 0
            AND ambience_score BETWEEN 0 AND 10 AND MOD(ambience_score, 0.5) = 0
            AND recommendation IN ('RECOMMENDED', 'NEUTRAL', 'NOT_RECOMMENDED')
            AND NOT REGEXP_LIKE(recommendation, '[[:space:]]')
            AND CHAR_LENGTH(body) BETWEEN 5 AND 500
            AND NOT REGEXP_LIKE(body, '^[[:space:]]|[[:space:]]$'))
        OR (status = 'PUBLISHED'
            AND taste_score IS NULL AND value_score IS NULL
            AND service_score IS NULL AND ambience_score IS NULL
            AND recommendation IS NULL AND body IS NULL)),
    CONSTRAINT chk_review_submission_result CHECK (
        (status = 'WAITING' AND published_review_id IS NULL
            AND published_at IS NULL AND withdrawn_at IS NULL)
        OR (status = 'WITHDRAWN' AND published_review_id IS NULL AND published_at IS NULL
            AND withdrawn_at IS NOT NULL AND withdrawn_at >= created_at)
        OR (status = 'PUBLISHED' AND published_review_id IS NOT NULL
            AND published_review_id BETWEEN 1 AND 9223372036854775807
            AND published_at IS NOT NULL AND published_at >= created_at AND withdrawn_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
    COMMENT='新店申请附带的私有待发布点评及发布回执';

-- 同review模块内可建立外键；跨表状态和文件用途/归属由后续应用事务检查，不用触发器越界。
CREATE TABLE review_submission_photos (
    submission_id BIGINT UNSIGNED NOT NULL COMMENT '所属待发布点评',
    position TINYINT UNSIGNED NOT NULL COMMENT '展示顺序0至4，最多五图',
    media_file_id BIGINT UNSIGNED NOT NULL COMMENT 'file模块REVIEW用途文件逻辑ID',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (submission_id, position),
    UNIQUE KEY uk_review_submission_photo_file (media_file_id),
    CONSTRAINT fk_review_submission_photo_submission FOREIGN KEY (submission_id)
        REFERENCES review_submissions(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_review_submission_photo_position CHECK (position BETWEEN 0 AND 4),
    CONSTRAINT chk_review_submission_photo_ids CHECK (
        submission_id BETWEEN 1 AND 9223372036854775807
        AND media_file_id BETWEEN 1 AND 9223372036854775807)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
    COMMENT='待发布点评的有序私有图片关联，发布时转移至正式点评';
