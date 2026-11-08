-- notification模块拥有公告及个人状态；全体公告不生成逐用户投递行，不补造历史公告。
CREATE TABLE notification_announcements (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '公告ID',
    client_request_id BINARY(16) NOT NULL COMMENT '发布请求UUID，按发布人去重',
    request_hash BINARY(32) NOT NULL COMMENT '规范化发布内容SHA-256摘要',
    title VARCHAR(120) NOT NULL COMMENT '公告标题',
    content TEXT NOT NULL COMMENT '公告正文，最多10000字符',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PUBLISHED' COMMENT 'PUBLISHED已发布、WITHDRAWN已撤回',
    published_by BIGINT UNSIGNED NOT NULL COMMENT '发布管理员用户逻辑ID',
    published_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '发布时间',
    withdrawn_by BIGINT UNSIGNED NULL COMMENT '撤回管理员用户逻辑ID',
    withdrawn_at TIMESTAMP(6) NULL COMMENT '首次撤回时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_announcement_publish_request (published_by,client_request_id),
    KEY idx_announcement_status_time (status,published_at DESC,id DESC),
    CONSTRAINT chk_announcement_publisher CHECK (published_by BETWEEN 1 AND 9223372036854775807),
    CONSTRAINT chk_announcement_title CHECK (CHAR_LENGTH(TRIM(title)) BETWEEN 1 AND 120),
    CONSTRAINT chk_announcement_content CHECK (CHAR_LENGTH(TRIM(content)) >= 1 AND CHAR_LENGTH(content) <= 10000),
    CONSTRAINT chk_announcement_lifecycle CHECK (
        (CAST(status AS BINARY) = 'PUBLISHED' AND withdrawn_by IS NULL AND withdrawn_at IS NULL)
        OR (CAST(status AS BINARY) = 'WITHDRAWN'
            AND withdrawn_by IS NOT NULL AND withdrawn_by BETWEEN 1 AND 9223372036854775807
            AND withdrawn_at IS NOT NULL AND withdrawn_at >= published_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='全体用户公告，无指定接收范围';

-- 稀疏个人状态：无记录表示未读；删除回执保留，避免公告再次出现在本人收件箱。
-- 仅对同模块公告建立RESTRICT外键，用户保持逻辑引用，不跨模块访问用户表。
CREATE TABLE notification_announcement_user_states (
    user_id BIGINT UNSIGNED NOT NULL COMMENT '用户逻辑ID',
    announcement_id BIGINT UNSIGNED NOT NULL COMMENT '公告ID',
    read_at TIMESTAMP(6) NULL COMMENT '首次已读时间',
    deleted_at TIMESTAMP(6) NULL COMMENT '本人删除时间',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '首次个人状态写入时间',
    PRIMARY KEY (user_id,announcement_id),
    KEY idx_announcement_state_announcement (announcement_id),
    CONSTRAINT fk_announcement_state_announcement FOREIGN KEY (announcement_id)
        REFERENCES notification_announcements(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_announcement_state_ids CHECK (
        user_id BETWEEN 1 AND 9223372036854775807 AND announcement_id BETWEEN 1 AND 9223372036854775807),
    CONSTRAINT chk_announcement_state_action CHECK (read_at IS NOT NULL OR deleted_at IS NOT NULL),
    CONSTRAINT chk_announcement_state_read CHECK (read_at IS NULL OR read_at >= created_at),
    CONSTRAINT chk_announcement_state_deleted CHECK (deleted_at IS NULL OR deleted_at >= created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='公告个人已读及删除状态';
