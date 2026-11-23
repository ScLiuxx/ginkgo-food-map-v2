-- 头像由user模块持有关联，文件归属及生命周期由file模块公开服务保证。
ALTER TABLE users ADD COLUMN avatar_file_id BIGINT NULL COMMENT '当前头像文件逻辑ID';
ALTER TABLE users ADD CONSTRAINT chk_users_avatar_file_id CHECK (avatar_file_id IS NULL OR avatar_file_id > 0);
ALTER TABLE users ADD UNIQUE KEY uk_users_avatar_file (avatar_file_id);
ALTER TABLE media_files DROP CHECK chk_media_files_purpose;
ALTER TABLE media_files ADD CONSTRAINT chk_media_files_purpose CHECK (
    purpose IN ('REVIEW','RESTAURANT','WINDOW','AVATAR')
    AND NOT REGEXP_LIKE(purpose, '[[:space:]]')
);
