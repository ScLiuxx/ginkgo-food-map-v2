package com.ginkgo.foodmap.review.mapper;

import com.ginkgo.foodmap.review.entity.ReviewCountsEntity;
import com.ginkgo.foodmap.review.entity.ReviewReadEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 本人视角只查询review表；身份由服务端传入，可见店铺由restaurant公开服务确定。 */
@Mapper
public interface MyReviewReadMapper {
    /** 只取目标ID，不拉取全部历史正文；窗口候选由独立查询取得。 */
    @Select(
            "SELECT DISTINCT restaurant_id FROM reviews WHERE author_user_id=#{author} AND"
                    + " restaurant_id IS NOT NULL")
    List<Long> targets(long author);

    String VISIBLE_TARGETS =
            """
            author_user_id=#{author} AND restaurant_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            """;

    /** 所有计数与页记录使用相同可见目标集合，历史条数和店铺组数分开返回。 */
    @Select(
            "<script>SELECT COUNT(DISTINCT restaurant_id) AS total_groups,COUNT(*) AS total_reviews"
                    + " FROM reviews WHERE "
                    + VISIBLE_TARGETS
                    + "</script>")
    ReviewCountsEntity counts(@Param("author") long author, @Param("ids") List<Long> ids);

    /** 先选每店最新代表，再按代表时间/ID倒序分页；同一时间以更大ID确定最新点评。 */
    @Select(
            """
<script>
SELECT id,restaurant_id,author_user_id,taste_score,value_score,service_score,ambience_score,
       overall_score,recommended,body,created_at,history_count
FROM (
    SELECT id,restaurant_id,author_user_id,taste_score,value_score,service_score,ambience_score,
           overall_score,is_recommended AS recommended,body,created_at,
           COUNT(*) OVER (PARTITION BY restaurant_id)-1 AS history_count,
           ROW_NUMBER() OVER (PARTITION BY restaurant_id ORDER BY created_at DESC,id DESC) AS representative
    FROM reviews WHERE
"""
                    + VISIBLE_TARGETS
                    + """
                      ) ranked WHERE representative=1
                      ORDER BY created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}
                      </script>
                      """)
    List<ReviewReadEntity> page(
            @Param("author") long author,
            @Param("ids") List<Long> ids,
            @Param("limit") int limit,
            @Param("offset") long offset);

    /** 独立窗口候选，不与餐厅ID合并去重。 */
    @Select(
            "SELECT DISTINCT window_id FROM reviews WHERE author_user_id=#{author} AND window_id IS"
                    + " NOT NULL")
    List<Long> windowTargets(long author);

    String MIXED_TARGETS =
            """
author_user_id=#{author} AND (
<choose><when test="ids.size()>0">restaurant_id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></when><otherwise>FALSE</otherwise></choose>
OR <choose><when test="windows.size()>0">window_id IN <foreach collection="windows" item="id" open="(" separator="," close=")">#{id}</foreach></when><otherwise>FALSE</otherwise></choose>)
""";

    @Select(
            "<script>SELECT COUNT(DISTINCT CONCAT(IF(window_id IS"
                    + " NULL,'R:','W:'),COALESCE(restaurant_id,window_id))) total_groups,COUNT(*)"
                    + " total_reviews FROM reviews WHERE "
                    + MIXED_TARGETS
                    + "</script>")
    ReviewCountsEntity mixedCounts(
            @Param("author") long author,
            @Param("ids") List<Long> ids,
            @Param("windows") List<Long> windows);

    @Select(
            """
<script>SELECT * FROM (
 SELECT id,restaurant_id,window_id,author_user_id,taste_score,value_score,service_score,ambience_score,overall_score,is_recommended AS recommended,body,created_at,
 COUNT(*) OVER(PARTITION BY restaurant_id,window_id)-1 history_count,
 ROW_NUMBER() OVER(PARTITION BY restaurant_id,window_id ORDER BY created_at DESC,id DESC) representative FROM reviews WHERE
"""
                    + MIXED_TARGETS
                    + """
) ranked WHERE representative=1 ORDER BY created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}</script>
""")
    List<ReviewReadEntity> mixedPage(
            @Param("author") long author,
            @Param("ids") List<Long> ids,
            @Param("windows") List<Long> windows,
            @Param("limit") int limit,
            @Param("offset") long offset);
}
