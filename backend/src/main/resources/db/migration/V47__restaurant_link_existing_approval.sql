-- 新店申请可直接关联已收录店铺；原申请类型与原始投稿快照保持不变。
ALTER TABLE restaurant_change_requests
    ADD COLUMN approval_mode VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '通过方式：CREATED/UPDATED/LINKED_EXISTING' AFTER status;

UPDATE restaurant_change_requests
SET approval_mode = CASE request_type WHEN 'CREATE' THEN 'CREATED' ELSE 'UPDATED' END
WHERE status = 'APPROVED';

ALTER TABLE restaurant_change_requests
    ADD CONSTRAINT chk_request_approval_mode CHECK (
        approval_mode IS NULL OR
        (status = 'APPROVED' AND (
            (request_type = 'CREATE' AND approval_mode IN ('CREATED', 'LINKED_EXISTING')) OR
            (request_type = 'UPDATE' AND approval_mode = 'UPDATED')
        ))
    );

-- 通知保存不可变处理方式，不依赖后续店铺/申请查询拼接展示内容。
ALTER TABLE notification_messages
    ADD COLUMN approval_mode VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '店铺申请通过方式，其他通知为空' AFTER decision;

UPDATE notification_messages
SET approval_mode = CASE request_type WHEN 'CREATE' THEN 'CREATED' ELSE 'UPDATED' END
WHERE notification_type = 'RESTAURANT_DECISION' AND decision = 'APPROVED';

ALTER TABLE notification_messages
    ADD CONSTRAINT chk_notification_approval_mode CHECK (
        approval_mode IS NULL OR
        (notification_type = 'RESTAURANT_DECISION' AND decision = 'APPROVED' AND (
            (request_type = 'CREATE' AND approval_mode IN ('CREATED', 'LINKED_EXISTING')) OR
            (request_type = 'UPDATE' AND approval_mode = 'UPDATED')
        ))
    );
