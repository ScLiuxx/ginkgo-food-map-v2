-- 餐厅类型与美食品类分离；历史分类只用于一次性回填，不按名称推断食堂。
-- 此迁移不创建窗口样例，不猜测校区或堂食/外卖资料，不改写旧餐厅的更新时间。
ALTER TABLE restaurants
    ADD COLUMN restaurant_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'REGULAR'
        COMMENT 'REGULAR普通餐厅、CANTEEN食堂' AFTER category_code,
    ADD COLUMN supports_dine_in BOOLEAN NULL COMMENT '是否支持堂食，NULL表示未知',
    ADD COLUMN supports_delivery BOOLEAN NULL COMMENT '是否支持外卖，NULL表示未知',
    ADD COLUMN campus_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'QINGSHUIHE清水河、SHAHE沙河，未知为NULL',
    ADD CONSTRAINT chk_restaurants_type CHECK (restaurant_type IN ('REGULAR', 'CANTEEN') AND CHAR_LENGTH(restaurant_type) = 7),
    ADD CONSTRAINT chk_restaurants_dining_modes CHECK (
        (supports_dine_in IS NULL OR supports_dine_in IN (0, 1))
        AND (supports_delivery IS NULL OR supports_delivery IN (0, 1))),
    ADD CONSTRAINT chk_restaurants_campus CHECK (campus_code IS NULL OR (
        campus_code IN ('QINGSHUIHE', 'SHAHE') AND NOT REGEXP_LIKE(campus_code, '[[:space:]]'))),
    -- 冗余组合唯一键用于下方同模块外键，保证窗口只能指向食堂，不能指向普通餐厅。
    ADD UNIQUE KEY uk_restaurants_id_type (id, restaurant_type),
    ADD INDEX idx_restaurants_type_visible (restaurant_type, deleted_at, id);

UPDATE restaurants SET restaurant_type = 'CANTEEN', updated_at = updated_at WHERE category_code = 'CANTEEN';

-- 窗口是独立实体，不再要求伪装为一条restaurant记录；名称/楼层不构成身份。
-- 换名保留ID；更换经营主体时关闭旧窗口并新建记录，旧点评不搬家。
CREATE TABLE canteen_windows (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '稳定窗口ID，不以名称楼层拼接',
    canteen_id BIGINT UNSIGNED NOT NULL COMMENT '所属食堂ID',
    canteen_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CANTEEN'
        COMMENT '外键类型锚点，固定CANTEEN，不作为客户端输入',
    name VARCHAR(40) NOT NULL COMMENT '窗口名称，允许同名',
    floor_number SMALLINT NOT NULL COMMENT '楼层数，负数表示地下，0表示地面层',
    category_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '美食品类，未知为空',
    description VARCHAR(500) NULL COMMENT '可选介绍，不代替菜品表',
    price_per_person SMALLINT UNSIGNED NULL COMMENT '参考整数元人均，未知为空',
    price_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNKNOWN'
        COMMENT 'UNKNOWN未知、EXACT填报、ESTIMATE估值',
    cover_file_id BIGINT UNSIGNED NULL COMMENT 'file模块逻辑ID，绑定和权限由应用服务验证',
    business_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'OPERATING'
        COMMENT 'OPERATING营业、CLOSED暂停或停止营业，保留历史点评',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '真实创建账号逻辑ID',
    updated_by BIGINT UNSIGNED NULL COMMENT '最近修改账号逻辑ID',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at TIMESTAMP(6) NULL COMMENT '软删除时间，停业只改经营状态',
    deleted_by BIGINT UNSIGNED NULL COMMENT '软删除操作者逻辑ID',
    PRIMARY KEY (id),
    KEY idx_canteen_windows_floor_category (canteen_id, floor_number, category_code, id),
    CONSTRAINT fk_canteen_windows_canteen FOREIGN KEY (canteen_id, canteen_type)
        REFERENCES restaurants(id, restaurant_type) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_canteen_windows_category FOREIGN KEY (category_code)
        REFERENCES restaurant_categories(code) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_canteen_windows_parent CHECK (
        canteen_id BETWEEN 1 AND 9223372036854775807 AND canteen_type = 'CANTEEN' AND CHAR_LENGTH(canteen_type) = 7),
    CONSTRAINT chk_canteen_windows_name CHECK (CHAR_LENGTH(name) BETWEEN 1 AND 40
        AND NOT REGEXP_LIKE(name, '^[[:space:]]|[[:space:]]$')),
    CONSTRAINT chk_canteen_windows_floor CHECK (floor_number BETWEEN -10 AND 100),
    CONSTRAINT chk_canteen_windows_description CHECK (description IS NULL OR (
        CHAR_LENGTH(description) BETWEEN 1 AND 500 AND NOT REGEXP_LIKE(description, '^[[:space:]]|[[:space:]]$'))),
    CONSTRAINT chk_canteen_windows_category CHECK (category_code IS NULL OR category_code <> 'CANTEEN'),
    CONSTRAINT chk_canteen_windows_price CHECK (
        (price_kind = 'UNKNOWN' AND price_per_person IS NULL)
        OR (price_kind IN ('EXACT', 'ESTIMATE') AND price_per_person IS NOT NULL AND price_per_person BETWEEN 1 AND 5000)),
    CONSTRAINT chk_canteen_windows_codes CHECK (
        NOT REGEXP_LIKE(price_kind, '[[:space:]]') AND NOT REGEXP_LIKE(business_status, '[[:space:]]')
        AND business_status IN ('OPERATING', 'CLOSED')),
    CONSTRAINT chk_canteen_windows_actors CHECK (
        created_by BETWEEN 1 AND 9223372036854775807
        AND (updated_by IS NULL OR updated_by BETWEEN 1 AND 9223372036854775807)
        AND (cover_file_id IS NULL OR cover_file_id BETWEEN 1 AND 9223372036854775807)),
    CONSTRAINT chk_canteen_windows_deletion CHECK (
        (deleted_at IS NULL AND deleted_by IS NULL)
        OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL AND deleted_by BETWEEN 1 AND 9223372036854775807))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
    COMMENT='食堂窗口档案，不存手工评分，不含堂食外卖及菜品表';
