-- notification模块独立拥有收件箱，不对用户/申请建立跨模块外键。
-- 只为本次上线后的审核结果创建通知，迁移不补发历史终态申请。
CREATE TABLE notification_messages (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '收件箱消息ID',
    recipient_user_id BIGINT UNSIGNED NOT NULL COMMENT '原申请人逻辑ID',
    request_id BIGINT UNSIGNED NOT NULL COMMENT '终态审核申请逻辑ID',
    request_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'CREATE新店或UPDATE纠错',
    decision VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'APPROVED或REJECTED',
    restaurant_name VARCHAR(120) NOT NULL COMMENT '审核时店名快照，仅作消息摘要',
    reject_reason VARCHAR(200) NULL COMMENT '驳回原因快照，通过时为空',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '通知入库时间',
    read_at TIMESTAMP(6) NULL COMMENT '首次已读时间；浏览列表不更新',
    PRIMARY KEY (id),
    CONSTRAINT uk_notification_request UNIQUE (recipient_user_id, request_id),
    KEY idx_notification_recipient_time (recipient_user_id, created_at DESC, id DESC),
    KEY idx_notification_recipient_unread (recipient_user_id, read_at, id),
    CONSTRAINT chk_notification_ids CHECK (
        recipient_user_id BETWEEN 1 AND 9223372036854775807
        AND request_id BETWEEN 1 AND 9223372036854775807),
    CONSTRAINT chk_notification_type CHECK (CAST(request_type AS BINARY) IN ('CREATE','UPDATE')),
    CONSTRAINT chk_notification_decision CHECK (CAST(decision AS BINARY) IN ('APPROVED','REJECTED')),
    CONSTRAINT chk_notification_name CHECK (CHAR_LENGTH(restaurant_name) BETWEEN 1 AND 120),
    CONSTRAINT chk_notification_reason CHECK (
        (CAST(decision AS BINARY) = 'APPROVED' AND reject_reason IS NULL)
        OR (CAST(decision AS BINARY) = 'REJECTED' AND reject_reason IS NOT NULL AND CHAR_LENGTH(reject_reason) BETWEEN 1 AND 200)),
    CONSTRAINT chk_notification_read CHECK (read_at IS NULL OR read_at >= created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审核系统通知，本人私有收件箱';
