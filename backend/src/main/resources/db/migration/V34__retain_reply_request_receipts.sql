-- 只保留授权和防重放所需标识，不保留正文；不设置外键，回执独立于已删除内容。
CREATE TABLE reply_request_receipts (
    author_user_id BIGINT UNSIGNED NOT NULL,
    client_request_id BINARY(16) NOT NULL,
    reply_id BIGINT UNSIGNED NOT NULL,
    review_id BIGINT UNSIGNED NOT NULL,
    deleted_at TIMESTAMP(6) NULL,
    PRIMARY KEY (author_user_id, client_request_id),
    UNIQUE KEY uk_reply_receipt_reply (reply_id),
    KEY idx_reply_receipt_review (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
    COMMENT='回复发布去重及作者删除回执，不随正文删除';

-- 现存回复一并保护；不补造已删除的历史回复或通知。
INSERT INTO reply_request_receipts(author_user_id,client_request_id,reply_id,review_id)
SELECT author_user_id,client_request_id,id,review_id FROM review_replies;
