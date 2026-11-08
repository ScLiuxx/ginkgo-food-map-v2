package com.ginkgo.foodmap.review.mapper;

import com.ginkgo.foodmap.review.entity.ReviewCountsEntity;
import com.ginkgo.foodmap.review.entity.ReviewReadEntity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 只读reviews；先选每人的最新代表再分页，历史深度不会改变主卡数量。 */
@Mapper
public interface ReviewReadMapper {
    String READ_COLUMNS =
            "id,author_user_id,taste_score,value_score,service_score,ambience_score,overall_score,is_recommended"
                + " AS recommended,body,created_at";

    /** 作者历史与当前主卡都以同一时间/ID顺序确定最新代表。 */
    @Select(
            "SELECT "
                    + READ_COLUMNS
                    + " FROM reviews WHERE restaurant_id=#{restaurant} AND author_user_id=#{author}"
                    + " ORDER BY created_at DESC,id DESC LIMIT 1")
    ReviewReadEntity latest(@Param("restaurant") long restaurant, @Param("author") long author);

    /** 校验游标锚点真实存在且属于该作者/餐厅，禁止只信任客户端编码内容。 */
    @Select(
            "SELECT "
                    + READ_COLUMNS
                    + " FROM reviews WHERE restaurant_id=#{restaurant} AND author_user_id=#{author}"
                    + " AND id=#{id}")
    ReviewReadEntity anchor(
            @Param("restaurant") long restaurant,
            @Param("author") long author,
            @Param("id") long id);

    /** 排他时间/ID游标，复用V5作者复合索引，额外取1条判断是否还有后续。 */
    @Select(
            "SELECT "
                    + READ_COLUMNS
                    + " FROM reviews WHERE restaurant_id=#{restaurant} AND author_user_id=#{author}"
                    + " AND (created_at < #{time} OR (created_at = #{time} AND id < #{id})) ORDER"
                    + " BY created_at DESC,id DESC LIMIT #{limit}")
    List<ReviewReadEntity> history(
            @Param("restaurant") long restaurant,
            @Param("author") long author,
            @Param("time") java.time.Instant time,
            @Param("id") long id,
            @Param("limit") int limit);

    /** 一次扫描得到独立作者数和全店条数；不依赖可能缺失的餐厅统计行。 */
    @Select(
            "SELECT COUNT(DISTINCT author_user_id) AS total_groups,COUNT(*) AS total_reviews FROM"
                    + " reviews WHERE restaurant_id=#{restaurant}")
    ReviewCountsEntity counts(long restaurant);

    /** 窗口按作者划分，以时间和ID共同定序。先过滤代表再LIMIT，不能分页普通点评后归组。 不查询用户/餐厅/媒体表；本页作者和照片由应用服务各一次批量补全。 */
    String PAGE_SQL =
            """
SELECT id,author_user_id,taste_score,value_score,service_score,ambience_score,
       overall_score,recommended,body,created_at,history_count
FROM (
    SELECT id,author_user_id,taste_score,value_score,service_score,ambience_score,
           overall_score,is_recommended AS recommended,body,created_at,
           COUNT(*) OVER (PARTITION BY author_user_id)-1 AS history_count,
           ROW_NUMBER() OVER (PARTITION BY author_user_id ORDER BY created_at DESC,id DESC) AS representative
    FROM reviews WHERE restaurant_id=#{restaurant}
) ranked WHERE representative=1
ORDER BY created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}
""";

    @Select(PAGE_SQL)
    List<ReviewReadEntity> page(
            @Param("restaurant") long restaurant,
            @Param("limit") int limit,
            @Param("offset") long offset);

    /** 当前店作者逻辑ID交由growth公开服务查询等级，不联查其他模块表。 */
    @Select("SELECT DISTINCT author_user_id FROM reviews WHERE restaurant_id=#{restaurant}")
    List<Long> authorIds(long restaurant);

    /** 先选最新代表，再按该点评有效点赞、作者等级、时间及ID排序后分页；全部参数绑定。 */
    @Select(
            """
<script>
SELECT ranked.* FROM (
    SELECT id,author_user_id,taste_score,value_score,service_score,ambience_score,
           overall_score,is_recommended AS recommended,body,created_at,
           COUNT(*) OVER (PARTITION BY author_user_id)-1 AS history_count,
           ROW_NUMBER() OVER (PARTITION BY author_user_id ORDER BY created_at DESC,id DESC) AS representative
    FROM reviews WHERE restaurant_id=#{restaurant}
) ranked WHERE representative=1
ORDER BY (SELECT COUNT(*) FROM content_likes likes
          WHERE likes.target_type='REVIEW' AND likes.target_id=ranked.id AND likes.active=1) DESC,
         CASE author_user_id
         <foreach collection="levels" index="user" item="level">WHEN #{user} THEN #{level}</foreach>
         ELSE 1 END DESC,
         created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}
</script>
""")
    List<ReviewReadEntity> rankedPage(
            @Param("restaurant") long restaurant,
            @Param("limit") int limit,
            @Param("offset") long offset,
            @Param("levels") java.util.Map<Long, Integer> levels);

    /** 作者历史与当前主卡都以同一时间/ID顺序确定最新代表。 */
    @Select(
            "SELECT "
                    + READ_COLUMNS
                    + " FROM reviews WHERE window_id=#{restaurant} AND author_user_id=#{author}"
                    + " ORDER BY created_at DESC,id DESC LIMIT 1")
    ReviewReadEntity windowLatest(
            @Param("restaurant") long restaurant, @Param("author") long author);

    /** 校验游标锚点真实存在且属于该作者/餐厅，禁止只信任客户端编码内容。 */
    @Select(
            "SELECT "
                    + READ_COLUMNS
                    + " FROM reviews WHERE window_id=#{restaurant} AND author_user_id=#{author} AND"
                    + " id=#{id}")
    ReviewReadEntity windowAnchor(
            @Param("restaurant") long restaurant,
            @Param("author") long author,
            @Param("id") long id);

    /** 排他时间/ID游标，复用V5作者复合索引，额外取1条判断是否还有后续。 */
    @Select(
            "SELECT "
                    + READ_COLUMNS
                    + " FROM reviews WHERE window_id=#{restaurant} AND author_user_id=#{author} AND"
                    + " (created_at < #{time} OR (created_at = #{time} AND id < #{id})) ORDER BY"
                    + " created_at DESC,id DESC LIMIT #{limit}")
    List<ReviewReadEntity> windowHistory(
            @Param("restaurant") long restaurant,
            @Param("author") long author,
            @Param("time") java.time.Instant time,
            @Param("id") long id,
            @Param("limit") int limit);

    /** 一次扫描得到独立作者数和全店条数；不依赖可能缺失的餐厅统计行。 */
    @Select(
            "SELECT COUNT(DISTINCT author_user_id) AS total_groups,COUNT(*) AS total_reviews FROM"
                    + " reviews WHERE window_id=#{restaurant}")
    ReviewCountsEntity windowCounts(long restaurant);

    /** 窗口按作者划分，以时间和ID共同定序。先过滤代表再LIMIT，不能分页普通点评后归组。 不查询用户/餐厅/媒体表；本页作者和照片由应用服务各一次批量补全。 */
    String WINDOW_PAGE_SQL =
            """
SELECT id,author_user_id,taste_score,value_score,service_score,ambience_score,
       overall_score,recommended,body,created_at,history_count
FROM (
    SELECT id,author_user_id,taste_score,value_score,service_score,ambience_score,
           overall_score,is_recommended AS recommended,body,created_at,
           COUNT(*) OVER (PARTITION BY author_user_id)-1 AS history_count,
           ROW_NUMBER() OVER (PARTITION BY author_user_id ORDER BY created_at DESC,id DESC) AS representative
    FROM reviews WHERE window_id=#{restaurant}
) ranked WHERE representative=1
ORDER BY created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}
""";

    @Select(WINDOW_PAGE_SQL)
    List<ReviewReadEntity> windowPage(
            @Param("restaurant") long restaurant,
            @Param("limit") int limit,
            @Param("offset") long offset);

    /** 当前店作者逻辑ID交由growth公开服务查询等级，不联查其他模块表。 */
    @Select("SELECT DISTINCT author_user_id FROM reviews WHERE window_id=#{restaurant}")
    List<Long> windowAuthorIds(long restaurant);

    /** 先选最新代表，再按该点评有效点赞、作者等级、时间及ID排序后分页；全部参数绑定。 */
    @Select(
            """
<script>
SELECT ranked.* FROM (
    SELECT id,author_user_id,taste_score,value_score,service_score,ambience_score,
           overall_score,is_recommended AS recommended,body,created_at,
           COUNT(*) OVER (PARTITION BY author_user_id)-1 AS history_count,
           ROW_NUMBER() OVER (PARTITION BY author_user_id ORDER BY created_at DESC,id DESC) AS representative
    FROM reviews WHERE window_id=#{restaurant}
) ranked WHERE representative=1
ORDER BY (SELECT COUNT(*) FROM content_likes likes
          WHERE likes.target_type='REVIEW' AND likes.target_id=ranked.id AND likes.active=1) DESC,
         CASE author_user_id
         <foreach collection="levels" index="user" item="level">WHEN #{user} THEN #{level}</foreach>
         ELSE 1 END DESC,
         created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}
</script>
""")
    List<ReviewReadEntity> windowRankedPage(
            @Param("restaurant") long restaurant,
            @Param("limit") int limit,
            @Param("offset") long offset,
            @Param("levels") java.util.Map<Long, Integer> levels);
}
