-- 榜单快照归restaurant模块，一份校区、周期榜单只保留最近成功结果。
-- 不预插空榜，也不回填历史点评；缺行与成功生成的空数组由后续应用服务区分。
CREATE TABLE restaurant_ranking_snapshots (
    campus_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '校区编码：QINGSHUIHE清水河、SHAHE沙河',
    period_type VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT '滚动周期：WEEK近168小时、MONTH近720小时',
    window_start_at TIMESTAMP(6) NOT NULL COMMENT '统计区间起点，包含该时刻',
    window_end_at TIMESTAMP(6) NOT NULL COMMENT '固定统计截止时刻T，不包含该时刻；数据年龄计算依据',
    generated_at TIMESTAMP(6) NOT NULL COMMENT '完整结果生成完成时刻，不等同于事务提交时间',
    rules_snapshot JSON NOT NULL COMMENT '本次生成使用的规则、分制及地理范围配置对象',
    items JSON NOT NULL COMMENT '已排序完整榜单数组，0至20项；成功空榜使用空数组',
    PRIMARY KEY (campus_code, period_type),
    -- 二进制精确匹配同时拒绝错误大小写与尾部空格，不依赖字符串排序规则的填充语义。
    CONSTRAINT chk_ranking_snapshot_campus CHECK (
        CAST(campus_code AS BINARY) IN ('QINGSHUIHE', 'SHAHE')
    ),
    CONSTRAINT chk_ranking_snapshot_period CHECK (
        CAST(period_type AS BINARY) IN ('WEEK', 'MONTH')
    ),
    CONSTRAINT chk_ranking_snapshot_window CHECK (window_start_at < window_end_at),
    CONSTRAINT chk_ranking_snapshot_generated CHECK (generated_at >= window_end_at),
    -- 数据库只约束JSON顶层形状与长度；名次、餐厅去重、规则及评分合法性由发布服务校验。
    CONSTRAINT chk_ranking_snapshot_rules CHECK (JSON_TYPE(rules_snapshot) = 'OBJECT'),
    CONSTRAINT chk_ranking_snapshot_items CHECK (
        JSON_TYPE(items) = 'ARRAY' AND JSON_LENGTH(items) <= 20
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '校区周月餐厅榜单的最近成功快照，由restaurant模块管理';
