-- 九个一级分类；复用已有稳定编码，停用旧细类但不删除，保留历史申请与快照的解释能力。
INSERT INTO restaurant_categories(code, name, sort_order, enabled)
VALUES ('CHINESE', '中餐地方菜', 2, TRUE),
       ('DRY_POT_FISH', '香锅烤鱼', 5, TRUE),
       ('INTERNATIONAL', '国际美食', 6, TRUE),
       ('DRINK', '饮品', 8, TRUE);

UPDATE restaurant_categories SET name='小吃快餐', sort_order=1, enabled=TRUE WHERE code='FAST_FOOD';
UPDATE restaurant_categories SET name='火锅串串', sort_order=3, enabled=TRUE WHERE code='HOT_POT';
UPDATE restaurant_categories SET name='烧烤烤肉', sort_order=4, enabled=TRUE WHERE code='BARBECUE';
UPDATE restaurant_categories SET name='甜品烘焙', sort_order=7, enabled=TRUE WHERE code='DESSERT';
UPDATE restaurant_categories SET name='其他美食', sort_order=9, enabled=TRUE WHERE code='OTHER';

-- 正式商户与食堂窗口迁移至大类；食堂身份由restaurant_type保存，不从经营品类推断或改写。
UPDATE restaurants SET category_code=CASE
    WHEN category_code='SICHUAN' THEN 'CHINESE'
    WHEN category_code IN ('SNACK','MAO_CAI','NOODLE','MALA_TANG','PORRIDGE_NOODLE','LIGHT_MEAL') THEN 'FAST_FOOD'
    WHEN category_code='CANTEEN' THEN 'OTHER'
    ELSE category_code END
WHERE category_code IN ('SICHUAN','SNACK','MAO_CAI','NOODLE','MALA_TANG','PORRIDGE_NOODLE','LIGHT_MEAL','CANTEEN');

UPDATE canteen_windows SET category_code=CASE
    WHEN category_code='SICHUAN' THEN 'CHINESE'
    WHEN category_code IN ('SNACK','MAO_CAI','NOODLE','MALA_TANG','PORRIDGE_NOODLE','LIGHT_MEAL') THEN 'FAST_FOOD'
    ELSE category_code END
WHERE category_code IN ('SICHUAN','SNACK','MAO_CAI','NOODLE','MALA_TANG','PORRIDGE_NOODLE','LIGHT_MEAL');

UPDATE restaurant_categories SET enabled=FALSE, sort_order=100+sort_order
WHERE code IN ('SICHUAN','SNACK','CANTEEN','MAO_CAI','NOODLE','MALA_TANG','PORRIDGE_NOODLE','LIGHT_MEAL');

-- 不修改申请原始JSON、摘要、审核快照或媒体关联；旧申请首次审核时应选择当前启用分类。
