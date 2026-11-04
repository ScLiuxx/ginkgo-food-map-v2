-- 每店独立的搜索投影版本；存量及新建商户从1开始，不改写业务资料和审计时间。
-- 后续同步必须同时传给ES external version；普通文档字段本身不会阻止乱序覆盖。
ALTER TABLE restaurants
    ADD COLUMN search_projection_version BIGINT NOT NULL DEFAULT 1 COMMENT '搜索投影版本，与业务事务一起递增',
    ADD CONSTRAINT chk_restaurant_search_projection_version CHECK (search_projection_version > 0);
