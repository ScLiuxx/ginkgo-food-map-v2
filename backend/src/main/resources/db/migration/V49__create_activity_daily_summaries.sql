-- statistics模块的每日汇总；Redis仅保存每日活跃账号集合。
-- 只建空表，不回补历史、不预填零值、不写入统计启用时间。
CREATE TABLE activity_daily_summaries (
    stat_date DATE NOT NULL COMMENT 'Asia/Shanghai自然日，主键',
    active_user_count BIGINT UNSIGNED NULL DEFAULT NULL COMMENT '当天普通登录用户活跃人数；NULL为尚未取得或无法确认',
    new_user_count BIGINT UNSIGNED NULL DEFAULT NULL COMMENT '当天新增普通用户数；NULL为尚未取得或无法确认',
    published_review_count BIGINT UNSIGNED NULL DEFAULT NULL COMMENT '当天正式发布点评数；后续删除不回扣，NULL为尚未取得或无法确认',
    collection_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNKNOWN'
        COMMENT 'NORMAL正常采集、PARTIAL部分采集、UNKNOWN无法确认',
    active_synced_at TIMESTAMP(6) NULL DEFAULT NULL COMMENT '活跃人数最近一次成功同步的时间，不是页面读取时间',
    PRIMARY KEY (stat_date),
    CONSTRAINT chk_activity_summary_counts CHECK (
        (active_user_count IS NULL OR active_user_count <= 9223372036854775807)
        AND (new_user_count IS NULL OR new_user_count <= 9223372036854775807)
        AND (published_review_count IS NULL OR published_review_count <= 9223372036854775807)
    ),
    CONSTRAINT chk_activity_summary_status CHECK (
        CAST(collection_status AS BINARY) IN ('NORMAL', 'PARTIAL', 'UNKNOWN')
    ),
    CONSTRAINT chk_activity_summary_active_sync CHECK (
        (active_user_count IS NULL AND active_synced_at IS NULL)
        OR (active_user_count IS NOT NULL AND active_synced_at IS NOT NULL)
    ),
    -- 正常采集必须有三个已知计数；当前日仍可继续变化，不代表已覆盖完整24小时。
    CONSTRAINT chk_activity_summary_normal CHECK (
        CAST(collection_status AS BINARY) <> 'NORMAL'
        OR (active_user_count IS NOT NULL AND new_user_count IS NOT NULL
            AND published_review_count IS NOT NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '普通用户每日使用统计汇总，不保存账号或点评明细';
