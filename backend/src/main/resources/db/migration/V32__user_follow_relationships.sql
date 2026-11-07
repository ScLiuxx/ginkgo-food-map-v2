-- community模块保存有向关注关系；取消关注保留原记录，以保留首次关系事实。
-- 两端均为user模块的逻辑ID，不建立跨模块外键；不在此处生成通知或Feed数据。
CREATE TABLE user_follows (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '关注关系主键',
    follower_user_id BIGINT UNSIGNED NOT NULL COMMENT '发起关注的用户逻辑ID',
    followed_user_id BIGINT UNSIGNED NOT NULL COMMENT '被关注的用户逻辑ID',
    active BOOLEAN NOT NULL DEFAULT TRUE COMMENT '1当前关注，0已取消关注',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '首次建立关系的时间',
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '最近一次关系修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_follows_pair (follower_user_id, followed_user_id),
    KEY idx_user_follows_follower (follower_user_id, active, id DESC),
    KEY idx_user_follows_followed (followed_user_id, active, id DESC),
    CONSTRAINT chk_user_follows_ids CHECK (
        follower_user_id BETWEEN 1 AND 9223372036854775807
        AND followed_user_id BETWEEN 1 AND 9223372036854775807
    ),
    CONSTRAINT chk_user_follows_not_self CHECK (follower_user_id <> followed_user_id),
    CONSTRAINT chk_user_follows_active CHECK (active IN (0, 1)),
    CONSTRAINT chk_user_follows_time CHECK (updated_at >= created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '用户有向关注关系';

-- 两种名单独立控制公开范围，老用户均默认私密；不改变收藏隐私、凭证或账号版本。
ALTER TABLE users
    ADD COLUMN following_public BOOLEAN NOT NULL DEFAULT FALSE COMMENT '关注名单是否向其他登录用户公开',
    ADD COLUMN followers_public BOOLEAN NOT NULL DEFAULT FALSE COMMENT '粉丝名单是否向其他登录用户公开',
    ADD CONSTRAINT chk_users_following_public CHECK (following_public IN (0, 1)),
    ADD CONSTRAINT chk_users_followers_public CHECK (followers_public IN (0, 1));
