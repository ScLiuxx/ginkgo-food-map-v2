-- 本人待审申请支持修改和撤回；驳回后重提生成新申请并保留原记录。
-- 不改变既有申请的业务快照、图片关联或审核结果，只补充撤回时间与新申请来源关联。
ALTER TABLE restaurant_change_requests
    ADD COLUMN previous_request_id BIGINT UNSIGNED NULL
        COMMENT '驳回后重提所依据的原申请ID；普通首次提交为空' AFTER restaurant_id,
    ADD COLUMN withdrawn_at TIMESTAMP(6) NULL
        COMMENT '本人撤回待审申请的时间；仅WITHDRAWN有值' AFTER reviewed_at;

-- 旧约束只允许三种状态，需先删除再按新状态集合重建；分步执行避免同名约束在同一条ALTER中反复。
ALTER TABLE restaurant_change_requests
    DROP CHECK chk_restaurant_change_status,
    DROP CHECK chk_restaurant_change_target_state,
    DROP CHECK chk_restaurant_change_review_state;

ALTER TABLE restaurant_change_requests
    MODIFY COLUMN status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING待审、APPROVED通过、REJECTED驳回、WITHDRAWN本人撤回',
    ADD CONSTRAINT chk_restaurant_change_status CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')
        AND CHAR_LENGTH(status) = CHAR_LENGTH(TRIM(status))
    ),
    -- 撤回只适用于一审未完成的申请，因此CREATE仍无商户ID，UPDATE保留原商户ID。
    ADD CONSTRAINT chk_restaurant_change_target_state CHECK (
        (request_type = 'UPDATE' AND restaurant_id IS NOT NULL)
        OR (request_type = 'CREATE' AND (
            (status = 'APPROVED' AND restaurant_id IS NOT NULL)
            OR (status IN ('PENDING', 'REJECTED', 'WITHDRAWN') AND restaurant_id IS NULL)
        ))
    ),
    -- 撤回不是审核终态：审核字段必须为空且撤回时间必填；其他状态不允许携带撤回时间。
    ADD CONSTRAINT chk_restaurant_change_review_state CHECK (
        (status = 'PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL
            AND reject_reason IS NULL AND approved_data IS NULL AND before_data IS NULL
            AND withdrawn_at IS NULL)
        OR (status = 'WITHDRAWN' AND reviewed_by IS NULL AND reviewed_at IS NULL
            AND reject_reason IS NULL AND approved_data IS NULL AND before_data IS NULL
            AND withdrawn_at IS NOT NULL)
        OR (status = 'APPROVED' AND withdrawn_at IS NULL
            AND reviewed_by IS NOT NULL AND reviewed_by > 0 AND reviewed_at IS NOT NULL
            AND reject_reason IS NULL AND approved_data IS NOT NULL AND JSON_TYPE(approved_data) = 'OBJECT'
            AND ((request_type = 'CREATE' AND before_data IS NULL)
                OR (request_type = 'UPDATE' AND before_data IS NOT NULL AND JSON_TYPE(before_data) = 'OBJECT')))
        OR (status = 'REJECTED' AND withdrawn_at IS NULL
            AND reviewed_by IS NOT NULL AND reviewed_by > 0 AND reviewed_at IS NOT NULL
            AND reject_reason IS NOT NULL AND NOT REGEXP_LIKE(reject_reason, '^[[:space:]]*$')
            AND approved_data IS NULL AND before_data IS NULL)
    ),
    -- 重提只能指向另一条历史申请；自引用由服务端在锁定原申请后写入，不在CHECK中比较自增列。
    -- 外键阻止删除仍被新申请引用的原驳回记录。
    ADD CONSTRAINT chk_restaurant_change_previous CHECK (
        previous_request_id IS NULL OR previous_request_id > 0
    ),
    ADD CONSTRAINT fk_restaurant_change_previous FOREIGN KEY (previous_request_id)
        REFERENCES restaurant_change_requests(id) ON DELETE RESTRICT ON UPDATE RESTRICT;
