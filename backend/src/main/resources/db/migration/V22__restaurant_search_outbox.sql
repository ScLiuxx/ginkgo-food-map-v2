-- 搜索同步任务只记录商户及持久投影版本，不复制账号、审核原文或整份搜索文档。
-- 本迁移不为存量商户生成任务：历史数据由后续独立全量导入负责。
CREATE TABLE restaurant_search_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务ID，不作为事务提交水位',
    restaurant_id BIGINT UNSIGNED NOT NULL COMMENT '需要重建搜索投影的商户ID',
    projection_version BIGINT NOT NULL COMMENT '产生任务时的商户投影版本',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '本阶段只写PENDING，尚无消费者',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '任务记录时间，不作为投影版本',
    PRIMARY KEY (id),
    UNIQUE KEY uk_restaurant_search_outbox_version (restaurant_id, projection_version),
    KEY idx_restaurant_search_outbox_pending (status, id),
    CONSTRAINT chk_restaurant_search_outbox_identity CHECK (restaurant_id BETWEEN 1 AND 9223372036854775807),
    CONSTRAINT chk_restaurant_search_outbox_version CHECK (projection_version > 0),
    CONSTRAINT chk_restaurant_search_outbox_status CHECK (status IN ('PENDING','PROCESSING','SUCCEEDED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
    COMMENT='商户搜索事务Outbox，后续消费者按ID重新读取完整投影';
-- 不设置级联外键：任务生命周期独立，不能因物理删除资料而静默丢失待处理任务。
