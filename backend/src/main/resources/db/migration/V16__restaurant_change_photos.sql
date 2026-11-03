-- 图片仍由file模块管理；商户待审图与点评图分用途，旧文件保持REVIEW用途。
ALTER TABLE media_files ADD COLUMN purpose VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
    NOT NULL DEFAULT 'REVIEW' COMMENT 'REVIEW点评图、RESTAURANT商户待审图';
ALTER TABLE media_files ADD CONSTRAINT chk_media_files_purpose CHECK (
    purpose IN ('REVIEW','RESTAURANT') AND NOT REGEXP_LIKE(purpose, '[[:space:]]'));

-- 跨模块文件ID仅逻辑关联；归属、用途和状态通过file公开服务在同一事务绑定。
CREATE TABLE restaurant_change_photos (
    request_id BIGINT UNSIGNED NOT NULL,
    position TINYINT UNSIGNED NOT NULL,
    media_file_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (request_id,position),
    UNIQUE KEY uk_restaurant_change_photo_file (media_file_id),
    CONSTRAINT fk_restaurant_change_photo_request FOREIGN KEY (request_id)
        REFERENCES restaurant_change_requests(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_restaurant_change_photo_position CHECK (position BETWEEN 0 AND 4),
    CONSTRAINT chk_restaurant_change_photo_file CHECK (media_file_id BETWEEN 1 AND 9223372036854775807)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商户待审申请照片，未审核不公开';
