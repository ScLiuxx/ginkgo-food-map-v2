-- 用户纠错仅创建待审快照，不更改正式商户。审核操作后续通过独立权限入口实现。
CREATE TABLE restaurant_change_requests (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    restaurant_id BIGINT UNSIGNED NOT NULL,
    submitted_by BIGINT UNSIGNED NOT NULL,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash BINARY(32) NOT NULL,
    proposed_data JSON NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_restaurant_change_request (submitted_by,client_request_id),
    INDEX idx_restaurant_change_queue (status,created_at,id),
    INDEX idx_restaurant_change_target (restaurant_id,created_at,id),
    CONSTRAINT fk_restaurant_change_target FOREIGN KEY (restaurant_id) REFERENCES restaurants(id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_restaurant_change_actor CHECK (submitted_by > 0),
    CONSTRAINT chk_restaurant_change_pending CHECK (status = 'PENDING')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商户资料待审核申请';
