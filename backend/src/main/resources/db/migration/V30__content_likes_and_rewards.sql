-- 点评与回复共用关系表，取消后保留首次关系，防止重新点赞重复提醒或发奖。
CREATE TABLE content_likes (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
 target_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 target_id BIGINT UNSIGNED NOT NULL,
 user_id BIGINT UNSIGNED NOT NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 PRIMARY KEY(id),
 UNIQUE KEY uk_content_like_actor(target_type,target_id,user_id),
 KEY idx_content_like_target(target_type,target_id,active),
 KEY idx_content_like_user(user_id,active,id),
 CONSTRAINT chk_content_like_type CHECK(CAST(target_type AS BINARY) IN ('REVIEW','REPLY')),
 CONSTRAINT chk_content_like_ids CHECK(target_id BETWEEN 1 AND 9223372036854775807 AND user_id BETWEEN 1 AND 9223372036854775807),
 CONSTRAINT chk_content_like_active CHECK(active IN (0,1)),
 CONSTRAINT chk_content_like_time CHECK(updated_at>=created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 扩展原通知来源，不保存被赞正文，旧通知与首次已读时间保持原值。
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
            AND restaurant_name IS NULL AND reject_reason IS NULL));


-- 仅获赞奖励填写点赞者和业务日期；旧类型允许空值，旧获赞记录不补造来源。
ALTER TABLE growth_rewards
 ADD actor_user_id BIGINT UNSIGNED NULL,
 ADD business_date DATE NULL,
 ADD KEY idx_growth_like_daily(user_id,reward_type,business_date,actor_user_id),
 ADD CONSTRAINT chk_growth_like_actor CHECK(actor_user_id IS NULL OR actor_user_id BETWEEN 1 AND 9223372036854775807),
 ADD CONSTRAINT chk_growth_like_fields CHECK(
  (CAST(reward_type AS BINARY)='REVIEW_LIKED' AND ((actor_user_id IS NULL AND business_date IS NULL) OR (actor_user_id IS NOT NULL AND business_date IS NOT NULL AND actor_user_id<>user_id)))
  OR (CAST(reward_type AS BINARY)<>'REVIEW_LIKED' AND actor_user_id IS NULL AND business_date IS NULL));
