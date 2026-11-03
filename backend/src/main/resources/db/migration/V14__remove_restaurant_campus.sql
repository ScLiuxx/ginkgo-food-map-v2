-- 商户位置由真实坐标表示，用户距离由请求原点计算；不再维护校区归属。
ALTER TABLE restaurants
    DROP CHECK chk_restaurants_campus,
    DROP COLUMN campus_code;
