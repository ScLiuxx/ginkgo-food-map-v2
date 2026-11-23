-- 旧审核来源保留；管理员直接图片独立记录真实上传管理员，不伪造审核申请。
ALTER TABLE restaurant_photos
    DROP CHECK chk_restaurant_photo_references,
    MODIFY source_request_id BIGINT UNSIGNED NULL COMMENT '审核来源；管理员直接维护时为空',
    ADD uploaded_by BIGINT UNSIGNED NULL COMMENT '管理员直接照片的上传者，非客户端输入',
    ADD CONSTRAINT chk_restaurant_photo_references CHECK (
        restaurant_id BETWEEN 1 AND 9223372036854775807
        AND media_file_id BETWEEN 1 AND 9223372036854775807
        AND ((source_request_id IS NOT NULL AND source_request_id BETWEEN 1 AND 9223372036854775807 AND uploaded_by IS NULL)
            OR (source_request_id IS NULL AND uploaded_by IS NOT NULL AND uploaded_by BETWEEN 1 AND 9223372036854775807)));
