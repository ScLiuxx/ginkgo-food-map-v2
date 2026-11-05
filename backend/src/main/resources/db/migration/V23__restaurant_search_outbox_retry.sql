-- 单实例串行消费：任务处理期间仍为PENDING，崩溃后自然重读，不引入租约或领取状态。
ALTER TABLE restaurant_search_outbox
    ADD COLUMN attempts INT NOT NULL DEFAULT 0 COMMENT '已持久记录的失败次数',
    ADD COLUMN next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '下次可尝试时间',
    ADD COLUMN last_error VARCHAR(200) NULL COMMENT '脱敏错误类型，不存商户正文或连接凭据',
    ADD COLUMN completed_at TIMESTAMP(6) NULL COMMENT '确认ES已处理后的时间',
    ADD CONSTRAINT chk_restaurant_search_outbox_attempts CHECK (attempts >= 0),
    ADD INDEX idx_restaurant_search_outbox_due (status, next_attempt_at, id);
