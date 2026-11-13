-- 先由已校验的旧布尔值逐条映射，再收紧新列；不改评分、统计、UUID、摘要及审计时间。
ALTER TABLE reviews
    ADD COLUMN recommendation VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'RECOMMENDED推荐、NEUTRAL一般、NOT_RECOMMENDED不推荐；无默认选择'
        AFTER is_recommended;

-- 显式保留updated_at，防止MySQL的ON UPDATE因内容回填而改写历史时间。
UPDATE reviews
SET recommendation = CASE is_recommended WHEN 1 THEN 'RECOMMENDED' ELSE 'NOT_RECOMMENDED' END,
    updated_at = updated_at;

ALTER TABLE reviews
    MODIFY COLUMN recommendation VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'RECOMMENDED推荐、NEUTRAL一般、NOT_RECOMMENDED不推荐；无默认选择',
    ADD CONSTRAINT chk_reviews_recommendation CHECK (
        recommendation IN ('RECOMMENDED', 'NEUTRAL', 'NOT_RECOMMENDED')
        AND NOT REGEXP_LIKE(recommendation, '[[:space:]]')),
    DROP CHECK chk_reviews_recommended,
    DROP COLUMN is_recommended;
