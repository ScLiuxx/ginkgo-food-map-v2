-- 收藏关系归restaurant模块；同用户同店只能存在一条记录，取消收藏时删除关系。
-- 餐厅和食堂均使用restaurants.id；不把食堂窗口ID作为收藏目标。
CREATE TABLE restaurant_favorites (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '收藏关系主键',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '收藏者用户逻辑ID，无跨模块外键',
    restaurant_id BIGINT UNSIGNED NOT NULL COMMENT '收藏的普通餐厅或食堂ID',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '收藏时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_restaurant_favorite_user_target (user_id, restaurant_id),
    KEY idx_restaurant_favorites_target (restaurant_id),
    KEY idx_restaurant_favorites_user_time (user_id, created_at DESC, id DESC),
    CONSTRAINT chk_restaurant_favorites_ids CHECK (
        user_id BETWEEN 1 AND 9223372036854775807
        AND restaurant_id BETWEEN 1 AND 9223372036854775807
    ),
    -- 商户软删除不会删除关系，恢复后仍可关联；物理删除不级联清理收藏。
    CONSTRAINT fk_restaurant_favorites_restaurant FOREIGN KEY (restaurant_id)
        REFERENCES restaurants (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '店铺收藏关系';

-- 收藏列表默认仅本人可见；只新增隐私字段，不改写简介、凭证或账号版本。
ALTER TABLE users
    ADD COLUMN favorites_public BOOLEAN NOT NULL DEFAULT FALSE COMMENT '收藏列表是否向其他登录用户公开',
    ADD CONSTRAINT chk_users_favorites_public CHECK (favorites_public IN (0, 1));
