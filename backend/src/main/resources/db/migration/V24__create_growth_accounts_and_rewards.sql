-- growth模块只保存经验汇总与奖励记录；用户、源内容及处置为跨模块逻辑引用。
-- 迁移只创建空表，不给现有用户初始化账户，也不补发历史贡献经验。
CREATE TABLE growth_accounts (
    user_id BIGINT UNSIGNED NOT NULL COMMENT '受益用户逻辑ID，同时作为成长账户主键',
    experience BIGINT NOT NULL DEFAULT 0 COMMENT '当前净经验，后续由计奖事务与有效奖励合计保持一致',
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '账户最后更新时间，不作并发版本号',
    PRIMARY KEY (user_id),
    CONSTRAINT chk_growth_account_user CHECK (user_id BETWEEN 1 AND 9223372036854775807),
    CONSTRAINT chk_growth_account_experience CHECK (experience >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='growth经验账户，同用户写入以账户行锁协调';

-- 原amount及来源在发奖后由应用保持不变；撤销只更新状态、撤销时间及处置引用。
-- 业务唯一键长期保留，不随点评硬删除或奖励撤销释放；暂不创建独立经验流水表。
CREATE TABLE growth_rewards (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '奖励记录ID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '受益账户ID，审核奖励属于投稿者而非管理员',
    reward_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '首次点评、新店通过、纠错通过或点评获赞',
    business_key VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端生成的有界业务键，不使用HTTP请求UUID替代奖励资格',
    source_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'REVIEW或RESTAURANT_REQUEST',
    source_id BIGINT UNSIGNED NOT NULL COMMENT '原点评或审核申请逻辑ID，源内容删除后仍保留',
    amount INT NOT NULL COMMENT '原来实际发放的正整数经验，撤销按此值扣回',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'GRANTED' COMMENT 'GRANTED有效，REVOKED已撤销',
    granted_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '原发放时间，撤销不覆盖',
    revoked_at TIMESTAMP(6) NULL COMMENT '撤销时间，只有REVOKED可填写',
    disposition_id BIGINT UNSIGNED NULL COMMENT '撤销对应的风控处置逻辑ID',
    PRIMARY KEY (id),
    CONSTRAINT uk_growth_reward_business UNIQUE (user_id, reward_type, business_key),
    KEY idx_growth_rewards_user_status (user_id, status),
    KEY idx_growth_rewards_source (source_type, source_id, user_id),
    CONSTRAINT fk_growth_rewards_account FOREIGN KEY (user_id) REFERENCES growth_accounts(user_id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_growth_reward_ids CHECK (
        user_id BETWEEN 1 AND 9223372036854775807
        AND source_id BETWEEN 1 AND 9223372036854775807
        AND (disposition_id IS NULL OR disposition_id BETWEEN 1 AND 9223372036854775807)),
    CONSTRAINT chk_growth_reward_amount CHECK (amount > 0),
    CONSTRAINT chk_growth_reward_key CHECK (
        CHAR_LENGTH(business_key) BETWEEN 1 AND 96
        AND REGEXP_LIKE(business_key, '^[A-Za-z0-9]', 'c')
        AND NOT REGEXP_LIKE(business_key, '[^A-Za-z0-9:_-]', 'c')),
    -- 二进制比较拒绝大小写变体及尾部空格，避免枚举被字符串排序规则宽松匹配。
    CONSTRAINT chk_growth_reward_source_type CHECK (
        CAST(source_type AS BINARY) IN ('REVIEW', 'RESTAURANT_REQUEST')),
    CONSTRAINT chk_growth_reward_type CHECK (
        CAST(reward_type AS BINARY) IN
            ('FIRST_STORE_REVIEW', 'NEW_STORE_APPROVED', 'CORRECTION_APPROVED', 'REVIEW_LIKED')),
    CONSTRAINT chk_growth_reward_source_pair CHECK (
        (CAST(reward_type AS BINARY) IN ('FIRST_STORE_REVIEW', 'REVIEW_LIKED')
            AND CAST(source_type AS BINARY) = 'REVIEW')
        OR (CAST(reward_type AS BINARY) IN ('NEW_STORE_APPROVED', 'CORRECTION_APPROVED')
            AND CAST(source_type AS BINARY) = 'RESTAURANT_REQUEST')),
    CONSTRAINT chk_growth_reward_status CHECK (CAST(status AS BINARY) IN ('GRANTED', 'REVOKED')),
    CONSTRAINT chk_growth_reward_revocation CHECK (
        (CAST(status AS BINARY) = 'GRANTED' AND revoked_at IS NULL AND disposition_id IS NULL)
        OR (CAST(status AS BINARY) = 'REVOKED'
            AND revoked_at IS NOT NULL AND disposition_id IS NOT NULL AND revoked_at >= granted_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='growth奖励资格与一次撤销事实，不随源内容删除';
