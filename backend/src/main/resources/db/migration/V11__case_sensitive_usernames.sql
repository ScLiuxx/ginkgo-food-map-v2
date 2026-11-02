-- 只改变用户名列的比较规则，使注册唯一性与登录查询都区分大小写。
-- 不缩短现有列，不改写历史用户名/哈希/角色；邮箱仍保持原排序规则。
ALTER TABLE users
    MODIFY COLUMN username VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs NOT NULL
        COMMENT '全局唯一用户名，区分大小写；新注册长度2至24，保留历史用户名';
