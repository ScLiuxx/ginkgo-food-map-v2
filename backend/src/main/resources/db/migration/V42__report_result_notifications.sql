-- 举报处理结果通过系统消息告知举报者（RISK-012）；不新增第二张通知表，也不补发历史举报。
-- decision 由 varchar(8) 扩为 varchar(16)，以容纳 DISMISSED；既有通知内容不变。
ALTER TABLE notification_messages DROP CHECK chk_notification_payload;

ALTER TABLE notification_messages
    MODIFY decision VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '审核通过/驳回或举报处理结果快照';

-- 举报结果只保存结果枚举与来源点评，不保存处理人、举报人或内部备注。
ALTER TABLE notification_messages
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
            AND restaurant_name IS NULL AND reject_reason IS NULL)
        OR (CAST(notification_type AS BINARY)='REPORT_RESULT'
            AND source_id IS NOT NULL AND review_id IS NOT NULL
            AND actor_user_id IS NULL AND request_id IS NULL AND request_type IS NULL
            AND decision IS NOT NULL AND CAST(decision AS BINARY) IN ('REMOVED','DISMISSED')
            AND restaurant_name IS NULL AND reject_reason IS NULL));
