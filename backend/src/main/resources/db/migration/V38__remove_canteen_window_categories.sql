-- 窗口由管理员按食堂和楼层维护，不再沿用普通餐厅的美食品类。
-- 保留窗口ID及其他资料、点评和评分；只移除窗口分类关系及其索引/约束。
ALTER TABLE canteen_windows
    DROP FOREIGN KEY fk_canteen_windows_category,
    DROP CHECK chk_canteen_windows_category,
    DROP INDEX idx_canteen_windows_floor_category,
    DROP COLUMN category_code,
    ADD INDEX idx_canteen_windows_floor (canteen_id, floor_number, id);
