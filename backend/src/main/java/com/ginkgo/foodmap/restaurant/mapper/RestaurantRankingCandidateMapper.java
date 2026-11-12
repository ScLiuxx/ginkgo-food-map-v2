package com.ginkgo.foodmap.restaurant.mapper;

import com.ginkgo.foodmap.restaurant.entity.RestaurantRankingCandidate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 仅查询restaurant所属表，先完整加载候选，不在校区筛选与计分之前截取Top20。 */
@Mapper
public interface RestaurantRankingCandidateMapper {
    /** 封面沿用正式相册position=0及已通过且同店的来源校验，不请求文件服务或OSS。 */
    @Select("""
        SELECT r.id, r.name, r.latitude, r.longitude, p.id AS cover_photo_id
        FROM restaurants r
        LEFT JOIN restaurant_photos p ON p.restaurant_id=r.id AND p.position=0
          AND EXISTS (SELECT 1 FROM restaurant_change_requests q WHERE q.id=p.source_request_id
                      AND q.status='APPROVED' AND q.restaurant_id=r.id)
        WHERE r.deleted_at IS NULL AND r.restaurant_type='REGULAR' AND r.business_status='OPERATING'
          AND r.needs_location=0
          AND r.latitude BETWEEN -90 AND 90 AND r.longitude BETWEEN -180 AND 180
        ORDER BY r.id
        """)
    @Options(useCache = false, timeout = 30)
    List<RestaurantRankingCandidate> selectCandidates();
}
