-- 保留申请原始快照和摘要；新建/纠错类型不再依赖审核后可能回填的restaurant_id推断。
-- V16及以前仅有PENDING申请，可以按原目标是否为空无歧义回填类型，不改写旧字段或图片关联。
ALTER TABLE restaurant_change_requests
    ADD COLUMN request_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'CREATE新店投稿、UPDATE已有商户纠错；由服务端确定，审核后不改变' AFTER restaurant_id,
    ADD COLUMN reviewed_by BIGINT UNSIGNED NULL COMMENT '审核人逻辑ID，由服务端填写',
    ADD COLUMN reviewed_at TIMESTAMP(6) NULL COMMENT '审核完成时间，由服务端填写',
    ADD COLUMN reject_reason VARCHAR(200) NULL COMMENT '仅驳回时填写非空原因',
    ADD COLUMN approved_data JSON NULL COMMENT '通过时实际生效的完整业务快照，包含管理员修订',
    ADD COLUMN before_data JSON NULL COMMENT '纠错通过前的正式业务快照；新店没有旧资料';

UPDATE restaurant_change_requests
SET request_type = CASE WHEN restaurant_id IS NULL THEN 'CREATE' ELSE 'UPDATE' END;

-- 仅增加审核存储能力；审核接口、正式资料写入和图片处理由后续业务事务实现。
-- 每个分支显式检查NULL，避免MySQL的CHECK遇到UNKNOWN时接受缺少审核信息的记录。
ALTER TABLE restaurant_change_requests
    MODIFY COLUMN request_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
        COMMENT 'CREATE新店投稿、UPDATE已有商户纠错；无默认值，必须由服务端写入',
    MODIFY COLUMN restaurant_id BIGINT UNSIGNED NULL
        COMMENT 'UPDATE为原商户ID；CREATE待审/驳回为空，通过后填入新建商户ID',
    MODIFY COLUMN status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING待审、APPROVED通过、REJECTED驳回',
    DROP CHECK chk_restaurant_change_pending,
    ADD CONSTRAINT chk_restaurant_change_type CHECK (
        request_type IN ('CREATE', 'UPDATE') AND CHAR_LENGTH(request_type) = CHAR_LENGTH(TRIM(request_type))
    ),
    ADD CONSTRAINT chk_restaurant_change_status CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED') AND CHAR_LENGTH(status) = CHAR_LENGTH(TRIM(status))
    ),
    ADD CONSTRAINT chk_restaurant_change_target_state CHECK (
        (request_type = 'UPDATE' AND restaurant_id IS NOT NULL)
        OR (request_type = 'CREATE' AND (
            (status = 'APPROVED' AND restaurant_id IS NOT NULL)
            OR (status IN ('PENDING', 'REJECTED') AND restaurant_id IS NULL)
        ))
    ),
    ADD CONSTRAINT chk_restaurant_change_review_state CHECK (
        (status = 'PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL
            AND reject_reason IS NULL AND approved_data IS NULL AND before_data IS NULL)
        OR (status = 'APPROVED' AND reviewed_by IS NOT NULL AND reviewed_by > 0 AND reviewed_at IS NOT NULL
            AND reject_reason IS NULL AND approved_data IS NOT NULL AND JSON_TYPE(approved_data) = 'OBJECT'
            AND ((request_type = 'CREATE' AND before_data IS NULL)
                OR (request_type = 'UPDATE' AND before_data IS NOT NULL AND JSON_TYPE(before_data) = 'OBJECT')))
        OR (status = 'REJECTED' AND reviewed_by IS NOT NULL AND reviewed_by > 0 AND reviewed_at IS NOT NULL
            AND reject_reason IS NOT NULL AND NOT REGEXP_LIKE(reject_reason, '^[[:space:]]*$')
            AND approved_data IS NULL AND before_data IS NULL)
    ),
    ADD INDEX idx_restaurant_change_reviewed (status, reviewed_at, id);
