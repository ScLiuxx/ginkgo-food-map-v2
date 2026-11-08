-- 旧窗口保留身份；新建请求以创建人和UUID去重，旧记录不参与客户端请求重放。
ALTER TABLE canteen_windows ADD client_request_id BINARY(16) NULL COMMENT '新建窗口请求UUID', ADD request_hash BINARY(32) NULL COMMENT '新建内容摘要', ADD UNIQUE KEY uk_window_create_request(created_by,client_request_id);
INSERT INTO window_review_statistics(window_id) SELECT w.id FROM canteen_windows w LEFT JOIN window_review_statistics s ON s.window_id=w.id WHERE s.window_id IS NULL;
-- 食堂历史整体点评继续保留，但不再作为公开食堂评分。窗口尚无评分时汇总为零人数/待评级。
INSERT INTO restaurant_review_statistics(restaurant_id) SELECT r.id FROM restaurants r LEFT JOIN restaurant_review_statistics s ON s.restaurant_id=r.id WHERE r.restaurant_type='CANTEEN' AND s.restaurant_id IS NULL;
UPDATE restaurant_review_statistics s JOIN restaurants r ON r.id=s.restaurant_id LEFT JOIN (
 SELECT w.canteen_id,SUM(x.review_count) reviews,SUM(x.participant_count) participants,SUM(x.recommend_count) recommended,
 AVG(ROUND(x.taste_sum/NULLIF(x.participant_count,0),2))*SUM(x.participant_count) taste,
 AVG(ROUND(x.value_sum/NULLIF(x.participant_count,0),2))*SUM(x.participant_count) value_score,
 AVG(ROUND(x.service_sum/NULLIF(x.participant_count,0),2))*SUM(x.participant_count) service,
 AVG(ROUND(x.ambience_sum/NULLIF(x.participant_count,0),2))*SUM(x.participant_count) ambience,
 AVG(ROUND(x.overall_score_sum/NULLIF(x.participant_count,0),2))*SUM(x.participant_count) overall
 FROM canteen_windows w JOIN window_review_statistics x ON x.window_id=w.id WHERE w.deleted_at IS NULL AND w.business_status='OPERATING' GROUP BY w.canteen_id
) a ON a.canteen_id=r.id
SET s.review_count=COALESCE(a.reviews,0),s.participant_count=COALESCE(a.participants,0),s.recommend_count=COALESCE(a.recommended,0),s.taste_sum=COALESCE(a.taste,0),s.value_sum=COALESCE(a.value_score,0),s.service_sum=COALESCE(a.service,0),s.ambience_sum=COALESCE(a.ambience,0),s.overall_score_sum=COALESCE(a.overall,0)
WHERE r.restaurant_type='CANTEEN';
UPDATE restaurants SET search_projection_version=search_projection_version+1,updated_at=updated_at WHERE restaurant_type='CANTEEN';
INSERT INTO restaurant_search_outbox(restaurant_id,projection_version) SELECT id,search_projection_version FROM restaurants WHERE restaurant_type='CANTEEN';
