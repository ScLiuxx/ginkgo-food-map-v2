-- 仅取消五字下限，保留非空、500码点上限和去首尾空白；不改写已有正文及审计时间。
ALTER TABLE reviews
    DROP CHECK chk_reviews_body,
    MODIFY COLUMN body VARCHAR(500) NOT NULL COMMENT '去首尾空白的非空纯文本，最多500码点',
    ADD CONSTRAINT chk_reviews_body CHECK (CHAR_LENGTH(body) BETWEEN 1 AND 500
        AND NOT REGEXP_LIKE(body, '^[[:space:]]|[[:space:]]$'));

-- 待发布、撤回内容沿用相同正文规则；已发布回执仍要求清空私有内容，状态约束保持完整。
ALTER TABLE review_submissions
    DROP CHECK chk_review_submission_content,
    MODIFY COLUMN body VARCHAR(500) NULL COMMENT '去首尾空白的非空正文，最多500码点；发布转移后清空',
    ADD CONSTRAINT chk_review_submission_content CHECK (
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
            AND CHAR_LENGTH(body) BETWEEN 1 AND 500
            AND NOT REGEXP_LIKE(body, '^[[:space:]]|[[:space:]]$'))
        OR (status = 'PUBLISHED'
            AND taste_score IS NULL AND value_score IS NULL
            AND service_score IS NULL AND ambience_score IS NULL
            AND recommendation IS NULL AND body IS NULL));
