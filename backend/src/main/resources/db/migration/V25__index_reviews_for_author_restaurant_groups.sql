-- 本人点评按作者检索、按店铺归组；保留已有商户视角索引，不改变点评或计分数据。
CREATE INDEX idx_reviews_author_restaurant_created_id
    ON reviews (author_user_id, restaurant_id, created_at, id);
