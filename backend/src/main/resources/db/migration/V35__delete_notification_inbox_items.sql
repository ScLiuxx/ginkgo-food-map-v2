-- 删除仅影响本人收件箱可见性；永久来源唯一键继续防止业务重试重新生成消息。
ALTER TABLE notification_messages
    ADD deleted_at TIMESTAMP(6) NULL COMMENT '收件人删除时间；NULL表示在收件箱可见',
    ADD CONSTRAINT chk_notification_deleted CHECK (deleted_at IS NULL OR deleted_at >= created_at),
    DROP INDEX idx_notification_recipient_time,
    DROP INDEX idx_notification_recipient_unread,
    DROP INDEX idx_notification_recipient_type_time,
    DROP INDEX idx_notification_recipient_type_unread,
    ADD KEY idx_notification_recipient_time (recipient_user_id,deleted_at,created_at DESC,id DESC),
    ADD KEY idx_notification_recipient_unread (recipient_user_id,deleted_at,read_at,id),
    ADD KEY idx_notification_recipient_type_time (recipient_user_id,deleted_at,notification_type,created_at DESC,id DESC),
    ADD KEY idx_notification_recipient_type_unread (recipient_user_id,deleted_at,notification_type,read_at,id);
