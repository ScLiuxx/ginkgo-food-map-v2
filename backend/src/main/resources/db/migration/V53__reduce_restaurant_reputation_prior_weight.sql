-- 长期口碑分改为C=3.5、m=2。重算派生列，保留既有排序索引和原始点评累计值。
ALTER TABLE restaurant_review_statistics
    MODIFY COLUMN reputation_score DECIMAL(12,8) GENERATED ALWAYS AS (
        CASE WHEN participant_count = 0 THEN NULL ELSE
            ((taste_sum * 0.70 + value_sum * 0.15 + service_sum * 0.10 + ambience_sum * 0.05) / 2
                + 7.0) / (participant_count + 2)
        END
    ) STORED COMMENT '五分制长期口碑分，八位小数，无评价为NULL';

-- 搜索投影异步读取新分数。版本递增与入队同事务，不修改商户的公开更新时间。
START TRANSACTION;
UPDATE restaurants r
JOIN restaurant_review_statistics s ON s.restaurant_id = r.id
SET r.search_projection_version = r.search_projection_version + 1, r.updated_at = r.updated_at
WHERE r.deleted_at IS NULL AND s.participant_count > 0;

INSERT INTO restaurant_search_outbox (restaurant_id, projection_version)
SELECT r.id, r.search_projection_version
FROM restaurants r
JOIN restaurant_review_statistics s ON s.restaurant_id = r.id
WHERE r.deleted_at IS NULL AND s.participant_count > 0;
COMMIT;
