-- 新商户申请尚无正式餐厅ID；与资料纠错共用待审队列，审核前不创建restaurants记录。
ALTER TABLE restaurant_change_requests MODIFY COLUMN restaurant_id BIGINT UNSIGNED NULL
    COMMENT '纠错目标ID；NULL表示新商户投稿';
