-- 普通餐厅和窗口使用明确的互斥关联；跨restaurant/review模块只保存逻辑ID，不新增跨模块外键或触发器。
-- 历史食堂点评仍保留原restaurant_id，不能猜测其归属窗口或复制到多个窗口。
-- 新发布必须由应用服务拒绝直接评价CANTEEN；本迁移仅建立结构，不伪称旧发布API已具备窗口能力。
ALTER TABLE reviews
    MODIFY COLUMN restaurant_id BIGINT UNSIGNED NULL COMMENT '普通餐厅或历史食堂逻辑ID，与window_id二选一',
    ADD COLUMN window_id BIGINT UNSIGNED NULL COMMENT '独立窗口逻辑ID，不填父食堂ID' AFTER restaurant_id,
    DROP CHECK chk_reviews_ids,
    ADD CONSTRAINT chk_reviews_author CHECK (author_user_id BETWEEN 1 AND 9223372036854775807),
    ADD CONSTRAINT chk_reviews_target CHECK (
        (restaurant_id IS NOT NULL AND window_id IS NULL AND restaurant_id BETWEEN 1 AND 9223372036854775807)
        OR (restaurant_id IS NULL AND window_id IS NOT NULL AND window_id BETWEEN 1 AND 9223372036854775807)),
    ADD INDEX idx_reviews_window_time (window_id, created_at, id),
    ADD INDEX idx_reviews_window_author_time (window_id, author_user_id, created_at, id);

-- 与评价归属保持同样口径；回执独立于评价生命周期，删除后仍保护旧请求不复活。
-- 不改动原(author_user_id,client_request_id)唯一范围，同一发布编号不可跨对象重复使用。
ALTER TABLE review_request_receipts
    MODIFY COLUMN restaurant_id BIGINT UNSIGNED NULL COMMENT '普通餐厅或历史食堂逻辑ID，与window_id二选一',
    ADD COLUMN window_id BIGINT UNSIGNED NULL COMMENT '窗口逻辑ID，回执不随点评删除' AFTER restaurant_id,
    ADD CONSTRAINT chk_review_receipts_target CHECK (
        (restaurant_id IS NOT NULL AND window_id IS NULL AND restaurant_id BETWEEN 1 AND 9223372036854775807)
        OR (restaurant_id IS NULL AND window_id IS NOT NULL AND window_id BETWEEN 1 AND 9223372036854775807));

-- 同restaurant模块维护窗口评分读模型，复用每账号每对象最新有效点评的累计规则。
-- 均分读取时使用sum/NULLIF(participant_count,0)，真实0分不能作为“未评分”。
CREATE TABLE window_review_statistics (
    window_id BIGINT UNSIGNED NOT NULL COMMENT '一窗口一行，和餐厅统计分离',
    review_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '含历史的全部点评条数',
    participant_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '参与计分的独立账号数',
    recommend_count INT UNSIGNED NOT NULL DEFAULT 0,
    taste_sum DECIMAL(16,2) NOT NULL DEFAULT 0,
    value_sum DECIMAL(16,2) NOT NULL DEFAULT 0,
    service_sum DECIMAL(16,2) NOT NULL DEFAULT 0,
    ambience_sum DECIMAL(16,2) NOT NULL DEFAULT 0,
    overall_score_sum DECIMAL(16,2) NOT NULL DEFAULT 0 COMMENT '最新代表点评的正式总分累计值',
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (window_id),
    CONSTRAINT fk_window_statistics_window FOREIGN KEY (window_id) REFERENCES canteen_windows(id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_window_statistics_id CHECK (window_id BETWEEN 1 AND 9223372036854775807),
    CONSTRAINT chk_window_statistics_counts CHECK (recommend_count <= participant_count AND participant_count <= review_count),
    CONSTRAINT chk_window_statistics_sums CHECK (
        taste_sum BETWEEN 0 AND participant_count * 10 AND value_sum BETWEEN 0 AND participant_count * 10
        AND service_sum BETWEEN 0 AND participant_count * 10 AND ambience_sum BETWEEN 0 AND participant_count * 10
        AND overall_score_sum BETWEEN 0 AND participant_count * 10)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
    COMMENT='可重建的窗口统计，食堂汇总由营业且有评分的窗口等权平均';
