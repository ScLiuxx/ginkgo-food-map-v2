-- 餐厅仅保留当前业务需要的数据：坐标统一约定GCJ-02，来源由导入批次资料留存。
-- row_version未参与餐厅并发控制；用户表的同名字段用于凭证更新，必须保留。
-- 同一ALTER原子完成约束调整与删列，避免出现约束仍引用已删除字段的中间状态。
ALTER TABLE restaurants
    DROP CHECK chk_restaurants_coordinate_system,
    DROP CHECK chk_restaurants_source,
    DROP CHECK chk_restaurants_machine_codes,
    DROP COLUMN coordinate_system,
    DROP COLUMN source_type,
    DROP COLUMN row_version,
    ADD CONSTRAINT chk_restaurants_machine_codes CHECK (
        CHAR_LENGTH(price_kind) = CHAR_LENGTH(TRIM(price_kind))
        AND CHAR_LENGTH(business_status) = CHAR_LENGTH(TRIM(business_status))
        AND (delete_reason_code IS NULL
            OR CHAR_LENGTH(delete_reason_code) = CHAR_LENGTH(TRIM(delete_reason_code)))
    );
