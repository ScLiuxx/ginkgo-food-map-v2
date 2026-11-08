-- 窗口相册与点评照片分离；最多五张由窗口主行锁下的应用规则保证。
ALTER TABLE media_files DROP CHECK chk_media_files_purpose;
ALTER TABLE media_files ADD CONSTRAINT chk_media_files_purpose CHECK (
    purpose IN ('REVIEW','RESTAURANT','WINDOW') AND NOT REGEXP_LIKE(purpose, '[[:space:]]'));

CREATE TABLE canteen_window_photos (
    window_id BIGINT UNSIGNED NOT NULL,
    media_file_id BIGINT UNSIGNED NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (window_id, media_file_id),
    UNIQUE KEY uk_window_photo_file (media_file_id),
    KEY idx_window_photo_order (window_id, created_at, media_file_id),
    CONSTRAINT fk_window_photo_window FOREIGN KEY (window_id)
        REFERENCES canteen_windows(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_window_photo_ids CHECK (
        window_id BETWEEN 1 AND 9223372036854775807
        AND media_file_id BETWEEN 1 AND 9223372036854775807)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='管理员窗口相册，媒体逻辑关联，首张为封面';
