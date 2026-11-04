-- 食堂是包含多种美食窗口的场所，不适用单一美食品类；普通餐厅仍须选择分类。
-- 保留字典外键及窗口分类，不重写原投稿、摘要或已经完成的审核快照。
ALTER TABLE restaurants
    MODIFY COLUMN category_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '普通餐厅的美食分类；食堂固定为NULL';

-- 规则迁移不伪装为用户编辑，保留已有更新时间及维护人。
UPDATE restaurants SET category_code = NULL, updated_at = updated_at WHERE restaurant_type = 'CANTEEN';

-- 显式判断NULL，避免MySQL CHECK将UNKNOWN当作合法结果放行。
ALTER TABLE restaurants ADD CONSTRAINT chk_restaurants_type_category CHECK (
    (restaurant_type = 'CANTEEN' AND category_code IS NULL)
    OR (restaurant_type = 'REGULAR' AND category_code IS NOT NULL)
);
