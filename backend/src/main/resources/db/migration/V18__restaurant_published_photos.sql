-- 正式相册独立于投稿图片；只建空表，不将历史已通过申请自动公开。
-- file模块持有文件登记，跨模块只保存逻辑ID，状态与归属由file公开服务校验。
CREATE TABLE restaurant_photos (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    restaurant_id BIGINT UNSIGNED NOT NULL,
    media_file_id BIGINT UNSIGNED NOT NULL,
    position INT UNSIGNED NOT NULL COMMENT '商户内追加顺序，从0开始；第一张为默认封面',
    source_request_id BIGINT UNSIGNED NOT NULL COMMENT '发布来源申请，保留审核追溯',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_restaurant_photo_file (media_file_id),
    UNIQUE KEY uk_restaurant_photo_position (restaurant_id, position),
    KEY idx_restaurant_photo_source (source_request_id, position, id),
    CONSTRAINT fk_restaurant_photo_restaurant FOREIGN KEY (restaurant_id)
        REFERENCES restaurants(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_restaurant_photo_request FOREIGN KEY (source_request_id)
        REFERENCES restaurant_change_requests(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_restaurant_photo_references CHECK (
        restaurant_id BETWEEN 1 AND 9223372036854775807
        AND media_file_id BETWEEN 1 AND 9223372036854775807
        AND source_request_id BETWEEN 1 AND 9223372036854775807),
    CONSTRAINT chk_restaurant_photo_position CHECK (position <= 2147483647)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审核通过后的正式商户相册；复用私有文件';
