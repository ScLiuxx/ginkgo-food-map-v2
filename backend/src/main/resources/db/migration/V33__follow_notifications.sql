-- 仅扩展已有通知的字段组合约束，首次关注来源为user_follows.id；不补发历史消息。
ALTER TABLE notification_messages DROP CHECK chk_notification_payload,
    ADD CONSTRAINT chk_notification_payload CHECK (
        (CAST(notification_type AS BINARY)='RESTAURANT_DECISION'
            AND request_id IS NOT NULL AND source_id=request_id AND request_type IS NOT NULL
            AND CAST(request_type AS BINARY) IN ('CREATE','UPDATE')
            AND decision IS NOT NULL AND CAST(decision AS BINARY) IN ('APPROVED','REJECTED')
            AND restaurant_name IS NOT NULL AND CHAR_LENGTH(restaurant_name) BETWEEN 1 AND 120
            AND review_id IS NULL AND actor_user_id IS NULL
            AND ((CAST(decision AS BINARY)='APPROVED' AND reject_reason IS NULL)
                OR (CAST(decision AS BINARY)='REJECTED' AND reject_reason IS NOT NULL AND CHAR_LENGTH(reject_reason) BETWEEN 1 AND 200)))
        OR (CAST(notification_type AS BINARY) IN ('REVIEW_REPLY','REVIEW_LIKED','REPLY_LIKED')
            AND review_id IS NOT NULL AND actor_user_id IS NOT NULL AND actor_user_id<>recipient_user_id
            AND request_id IS NULL AND request_type IS NULL AND decision IS NULL
            AND restaurant_name IS NULL AND reject_reason IS NULL)
        OR (CAST(notification_type AS BINARY)='USER_FOLLOWED'
            AND actor_user_id IS NOT NULL AND actor_user_id<>recipient_user_id AND review_id IS NULL
            AND request_id IS NULL AND request_type IS NULL AND decision IS NULL
            AND restaurant_name IS NULL AND reject_reason IS NULL));
