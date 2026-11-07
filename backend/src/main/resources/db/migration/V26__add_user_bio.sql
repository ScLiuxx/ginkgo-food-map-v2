-- 简介归user模块所有；已有账号以空简介开始，不修改邮箱、密码或任何凭证版本。
ALTER TABLE users
    ADD COLUMN bio VARCHAR(100) NOT NULL DEFAULT '' COMMENT '个人简介，最多100个Unicode字符，按纯文本展示';
