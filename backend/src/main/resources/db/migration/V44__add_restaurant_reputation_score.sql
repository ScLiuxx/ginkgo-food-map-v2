-- 长期口碑分为可重建的派生值：原始十分制累计和换算为五分制，C=3.5、m=5。
-- STORED生成列在同一条统计更新中自动维护，历史行自动回填；禁止客户端或业务代码另写分数。
-- 无计分用户返回NULL，参考分不能冒充真实评分。食堂仍由读取层使用窗口等权均分。
ALTER TABLE restaurant_review_statistics
    ADD COLUMN reputation_score DECIMAL(12,8) GENERATED ALWAYS AS (
        CASE WHEN participant_count = 0 THEN NULL ELSE
            ((taste_sum * 0.70 + value_sum * 0.15 + service_sum * 0.10 + ambience_sum * 0.05) / 2
                + 17.5) / (participant_count + 5)
        END
    ) STORED COMMENT '普通餐厅五分制长期口碑分，八位小数，无评价为NULL',
    ADD INDEX idx_restaurant_statistics_reputation (reputation_score DESC, participant_count DESC, restaurant_id);
