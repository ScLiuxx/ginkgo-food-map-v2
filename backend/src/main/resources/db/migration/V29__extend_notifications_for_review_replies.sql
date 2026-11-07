-- 在原收件箱扩展来源，保留既有审核消息的ID、摘要和已读时间，不创建第二张通知表。
ALTER TABLE notification_messages
    DROP CHECK chk_notification_ids,
    DROP CHECK chk_notification_type,
    DROP CHECK chk_notification_decision,
    DROP CHECK chk_notification_name,
    DROP CHECK chk_notification_reason,
    DROP INDEX uk_notification_request,
    MODIFY request_id BIGINT UNSIGNED NULL COMMENT '审核申请逻辑ID，仅审核消息填写',
    MODIFY request_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL,
    MODIFY decision VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL,
    MODIFY restaurant_name VARCHAR(120) NULL,
    ADD notification_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'RESTAURANT_DECISION' COMMENT '通知来源类型',
    ADD source_id BIGINT UNSIGNED NULL COMMENT '申请ID或回复ID，配合通知类型去重',
    ADD review_id BIGINT UNSIGNED NULL COMMENT '回复所属点评逻辑ID',
    ADD actor_user_id BIGINT UNSIGNED NULL COMMENT '回复者逻辑ID，不保存回复正文';

UPDATE notification_messages SET source_id=request_id;

ALTER TABLE notification_messages
    MODIFY source_id BIGINT UNSIGNED NOT NULL,
    ADD CONSTRAINT uk_notification_source UNIQUE (recipient_user_id,notification_type,source_id),
    ADD KEY idx_notification_recipient_type_time (recipient_user_id,notification_type,created_at DESC,id DESC),
    ADD KEY idx_notification_recipient_type_unread (recipient_user_id,notification_type,read_at,id),
    ADD CONSTRAINT chk_notification_ids CHECK (
        recipient_user_id BETWEEN 1 AND 9223372036854775807
        AND source_id BETWEEN 1 AND 9223372036854775807
        AND (request_id IS NULL OR request_id BETWEEN 1 AND 9223372036854775807)
        AND (review_id IS NULL OR review_id BETWEEN 1 AND 9223372036854775807)
        AND (actor_user_id IS NULL OR actor_user_id BETWEEN 1 AND 9223372036854775807)),
    -- 显式检查NULL，避免SQL CHECK遇到UNKNOWN时放过缺少关键来源字段的记录。
    ADD CONSTRAINT chk_notification_payload CHECK (
        (CAST(notification_type AS BINARY)='RESTAURANT_DECISION'
            AND request_id IS NOT NULL AND source_id=request_id AND request_type IS NOT NULL
            AND CAST(request_type AS BINARY) IN ('CREATE','UPDATE')
            AND decision IS NOT NULL AND CAST(decision AS BINARY) IN ('APPROVED','REJECTED')
            AND restaurant_name IS NOT NULL AND CHAR_LENGTH(restaurant_name) BETWEEN 1 AND 120
            AND review_id IS NULL AND actor_user_id IS NULL
            AND ((CAST(decision AS BINARY)='APPROVED' AND reject_reason IS NULL)
                OR (CAST(decision AS BINARY)='REJECTED' AND reject_reason IS NOT NULL AND CHAR_LENGTH(reject_reason) BETWEEN 1 AND 200)))
        OR (CAST(notification_type AS BINARY)='REVIEW_REPLY'
            AND review_id IS NOT NULL AND actor_user_id IS NOT NULL AND actor_user_id<>recipient_user_id
            AND request_id IS NULL AND request_type IS NULL AND decision IS NULL
            AND restaurant_name IS NULL AND reject_reason IS NULL));
