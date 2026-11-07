-- 本人申请按提交时间倒序读取；状态筛选在本人范围内执行，暂不增加第二个状态组合索引。
-- 只新增索引，不改写申请、审核快照或图片关系。
CREATE INDEX idx_restaurant_change_submitter_time
    ON restaurant_change_requests (submitted_by, created_at, id);
