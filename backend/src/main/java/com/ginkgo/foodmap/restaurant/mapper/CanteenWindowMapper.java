package com.ginkgo.foodmap.restaurant.mapper;

import com.ginkgo.foodmap.restaurant.dto.RestaurantReviewDelta;
import com.ginkgo.foodmap.restaurant.entity.CanteenWindowEntity;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 窗口资料与计分均属于restaurant；所有写入先锁父食堂再锁窗口。 */
@Mapper
public interface CanteenWindowMapper {
    String FROM =
            " FROM canteen_windows w JOIN restaurants r ON r.id=w.canteen_id LEFT JOIN"
                    + " window_review_statistics s ON s.window_id=w.id ";
    String COLUMNS =
            "w.*,r.name canteen_name,NULL"
                + " campus_code,r.address,r.latitude,r.longitude,r.needs_location,ROUND(s.overall_score_sum/NULLIF(s.participant_count,0),2)"
                + " score,ROUND(s.taste_sum/NULLIF(s.participant_count,0),2)"
                + " taste,ROUND(s.value_sum/NULLIF(s.participant_count,0),2)"
                + " value,ROUND(s.service_sum/NULLIF(s.participant_count,0),2)"
                + " service,ROUND(s.ambience_sum/NULLIF(s.participant_count,0),2)"
                + " ambience,ROUND(100*s.recommend_count/NULLIF(s.participant_count,0),2)"
                + " recommend_rate,COALESCE(s.review_count,0)"
                + " review_count,COALESCE(s.participant_count,0) participant_count";

    @Select(
            "<script>SELECT "
                    + COLUMNS
                    + FROM
                    + " WHERE w.deleted_at IS NULL AND r.deleted_at IS NULL AND w.id IN <foreach"
                    + " collection='ids' item='id' open='(' separator=','"
                    + " close=')'>#{id}</foreach></script>")
    List<CanteenWindowEntity> visibleBatch(@Param("ids") List<Long> ids);

    @Select("SELECT " + COLUMNS + FROM + " WHERE w.id=#{id}")
    CanteenWindowEntity find(long id);

    @Select(
            "SELECT "
                    + COLUMNS
                    + FROM
                    + " WHERE w.id=#{id} AND w.deleted_at IS NULL AND r.deleted_at IS NULL")
    CanteenWindowEntity visible(long id);

    @Select(
            "SELECT "
                    + COLUMNS
                    + FROM
                    + " WHERE w.canteen_id=#{parent} AND (#{management}=TRUE OR (w.deleted_at IS"
                    + " NULL AND r.deleted_at IS NULL)) ORDER BY w.floor_number,w.id")
    List<CanteenWindowEntity> list(
            @Param("parent") long parent, @Param("management") boolean management);

    @Select("SELECT id FROM canteen_windows WHERE id=#{id} FOR UPDATE")
    Long lock(long id);

    @Select("SELECT restaurant_type FROM restaurants WHERE id=#{id} AND deleted_at IS NULL")
    String parentType(long id);

    @Select("SELECT restaurant_type FROM restaurants WHERE id=#{id}")
    String existingParentType(long id);

    @Select(
            "SELECT "
                    + COLUMNS
                    + FROM
                    + " WHERE w.created_by=#{owner} AND w.client_request_id=#{uuid}")
    CanteenWindowEntity request(@Param("owner") long owner, @Param("uuid") byte[] uuid);

    @Insert(
            "INSERT INTO"
                + " canteen_windows(canteen_id,name,floor_number,description,price_kind,price_per_person,business_status,created_by,client_request_id,request_hash)"
                + " VALUES(#{canteenId},#{name},#{floorNumber},#{description},#{priceKind},#{pricePerPerson},#{businessStatus},#{createdBy},#{clientRequestId},#{requestHash})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CanteenWindowEntity row);

    @Update(
            "UPDATE canteen_windows SET"
                + " name=#{name},floor_number=#{floorNumber},description=#{description},price_kind=#{priceKind},price_per_person=#{pricePerPerson},business_status=#{businessStatus},updated_by=#{updatedBy}"
                + " WHERE id=#{id} AND deleted_at IS NULL")
    int update(CanteenWindowEntity row);

    @Update(
            "UPDATE canteen_windows SET"
                + " deleted_at=IF(#{deleted},CURRENT_TIMESTAMP(6),NULL),deleted_by=IF(#{deleted},#{owner},NULL),updated_by=#{owner}"
                + " WHERE id=#{id} AND ((#{deleted}=TRUE AND deleted_at IS NULL) OR"
                + " (#{deleted}=FALSE AND deleted_at IS NOT NULL))")
    int deleted(
            @Param("id") long id, @Param("owner") long owner, @Param("deleted") boolean deleted);

    @Insert(
            "INSERT INTO window_review_statistics(window_id) VALUES(#{id}) ON DUPLICATE KEY UPDATE"
                    + " window_id=window_id")
    int initialize(long id);

    @Update(
            "UPDATE window_review_statistics SET"
                + " review_count=review_count+#{d.reviews},participant_count=participant_count+#{d.participants},recommend_count=recommend_count+#{d.recommendations},taste_sum=taste_sum+#{d.taste},value_sum=value_sum+#{d.value},service_sum=service_sum+#{d.service},ambience_sum=ambience_sum+#{d.ambience},overall_score_sum=overall_score_sum+#{d.overall}"
                + " WHERE window_id=#{id}")
    int delta(@Param("id") long id, @Param("d") RestaurantReviewDelta delta);

    /** 父食堂计数汇总有效营业窗口；四维及总分按各窗口均分等权，不引入历史食堂点评。 */
    @Insert(
            """
INSERT INTO restaurant_review_statistics(restaurant_id,review_count,participant_count,recommend_count,taste_sum,value_sum,service_sum,ambience_sum,overall_score_sum)
SELECT #{parent},COALESCE(SUM(s.review_count),0),COALESCE(SUM(s.participant_count),0),COALESCE(SUM(s.recommend_count),0),
COALESCE(AVG(ROUND(s.taste_sum/NULLIF(s.participant_count,0),2))*SUM(s.participant_count),0),
COALESCE(AVG(ROUND(s.value_sum/NULLIF(s.participant_count,0),2))*SUM(s.participant_count),0),
COALESCE(AVG(ROUND(s.service_sum/NULLIF(s.participant_count,0),2))*SUM(s.participant_count),0),
COALESCE(AVG(ROUND(s.ambience_sum/NULLIF(s.participant_count,0),2))*SUM(s.participant_count),0),
COALESCE(AVG(ROUND(s.overall_score_sum/NULLIF(s.participant_count,0),2))*SUM(s.participant_count),0)
FROM canteen_windows w JOIN window_review_statistics s ON s.window_id=w.id
WHERE w.canteen_id=#{parent} AND w.deleted_at IS NULL AND w.business_status='OPERATING'
ON DUPLICATE KEY UPDATE review_count=VALUES(review_count),participant_count=VALUES(participant_count),recommend_count=VALUES(recommend_count),taste_sum=VALUES(taste_sum),value_sum=VALUES(value_sum),service_sum=VALUES(service_sum),ambience_sum=VALUES(ambience_sum),overall_score_sum=VALUES(overall_score_sum)
""")
    int refresh(long parent);
}
