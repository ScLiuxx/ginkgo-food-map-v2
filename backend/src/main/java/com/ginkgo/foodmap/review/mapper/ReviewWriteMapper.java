package com.ginkgo.foodmap.review.mapper;

import com.ginkgo.foodmap.review.entity.ReviewEntity;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** review独占SQL；不JOIN账号/餐厅/统计表，身份与父资源通过公开服务获得。 */
@Mapper
public interface ReviewWriteMapper {
    String COLUMNS =
            "id,restaurant_id,window_id,author_user_id,taste_score,value_score,service_score,ambience_score,overall_score,is_recommended"
                + " AS recommended,body,BIN_TO_UUID(client_request_id) AS"
                + " client_request_id,HEX(request_hash) AS request_hash,created_at";

    /** author+UUID是全店范围唯一，餐厅不同也不能复用同一次编号。 */
    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM reviews WHERE author_user_id=#{author} AND"
                    + " client_request_id=UUID_TO_BIN(#{uuid})")
    ReviewEntity request(@Param("author") long author, @Param("uuid") String uuid);

    /** 持有父店锁后读取代表，复合索引支持时间/ID倒序查一条。 */
    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM reviews WHERE restaurant_id=#{restaurant} AND author_user_id=#{author}"
                    + " ORDER BY created_at DESC,id DESC LIMIT 1")
    ReviewEntity latest(@Param("restaurant") long restaurant, @Param("author") long author);

    /** 窗口使用独立字段，不能与数值相同的餐厅合并代表点评。 */
    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM reviews WHERE window_id=#{window} AND author_user_id=#{author} ORDER"
                    + " BY created_at DESC,id DESC LIMIT 1")
    ReviewEntity latestWindow(@Param("window") long window, @Param("author") long author);

    /** 新点评保留历史；服务端时间不早于上一代表，时钟回拨时仍由新ID正确定序。 */
    @Insert(
            """
<script>
INSERT INTO reviews(restaurant_id,window_id,author_user_id,taste_score,value_score,service_score,ambience_score,is_recommended,body,client_request_id,request_hash,created_at)
VALUES(#{restaurantId,jdbcType=BIGINT},#{windowId,jdbcType=BIGINT},#{authorUserId},#{tasteScore},#{valueScore},#{serviceScore},#{ambienceScore},#{recommended},#{body},UUID_TO_BIN(#{clientRequestId}),UNHEX(#{requestHash}),
<choose><when test="createdAt != null">GREATEST(CURRENT_TIMESTAMP(6),#{createdAt})</when><otherwise>CURRENT_TIMESTAMP(6)</otherwise></choose>)
</script>
""")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ReviewEntity row);
}
