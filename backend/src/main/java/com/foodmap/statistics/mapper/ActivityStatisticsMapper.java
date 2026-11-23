package com.foodmap.statistics.mapper;

import com.foodmap.statistics.entity.ActivityDailySummary;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 仅访问统计汇总及statistics拥有的元数据键，不读取用户或点评表。 */
@Mapper
public interface ActivityStatisticsMapper {
    @Select("SELECT metadata_value FROM app_metadata WHERE metadata_key=#{key}")
    String metadata(String key);

    @Insert("INSERT IGNORE INTO app_metadata(metadata_key,metadata_value) VALUES(#{key},#{value})")
    int createMetadata(@Param("key") String key, @Param("value") String value);

    @Insert(
            """
            INSERT INTO app_metadata(metadata_key,metadata_value) VALUES(#{key},#{value})
            ON DUPLICATE KEY UPDATE metadata_value=#{value}
            """)
    int saveMetadata(@Param("key") String key, @Param("value") String value);

    @Select("SELECT * FROM activity_daily_summaries WHERE stat_date=#{date}")
    ActivityDailySummary day(LocalDate date);

    @Select(
            """
            SELECT * FROM activity_daily_summaries
            WHERE stat_date BETWEEN #{first} AND #{last} ORDER BY stat_date
            """)
    List<ActivityDailySummary> days(@Param("first") LocalDate first, @Param("last") LocalDate last);

    @Insert(
            """
INSERT IGNORE INTO activity_daily_summaries
    (stat_date,active_user_count,new_user_count,published_review_count,collection_status,active_synced_at)
VALUES(#{statDate},#{activeUserCount},#{newUserCount},#{publishedReviewCount},#{collectionStatus},#{activeSyncedAt})
""")
    int createDay(ActivityDailySummary day);

    /** 每类写入只更新自己的列，未知值保持未知，不把缺失日期初始化为完整零。 */
    @Update(
            """
UPDATE activity_daily_summaries SET new_user_count=new_user_count+1 WHERE stat_date=#{date}
""")
    int incrementUsers(LocalDate date);

    @Update(
            """
UPDATE activity_daily_summaries SET published_review_count=published_review_count+1 WHERE stat_date=#{date}
""")
    int incrementReviews(LocalDate date);

    @Update(
            "UPDATE activity_daily_summaries SET collection_status='PARTIAL' WHERE stat_date"
                    + " BETWEEN #{first} AND #{last}")
    int markPartial(@Param("first") LocalDate first, @Param("last") LocalDate last);

    /** 较旧同步不能覆盖较新同步；集合缩水保留已有下界并标记，绝不声称已恢复完整。 */
    @Update(
            """
UPDATE activity_daily_summaries
SET collection_status=CASE WHEN collection_status<>'NORMAL' OR active_user_count>#{count}
        THEN 'PARTIAL' ELSE collection_status END,
    active_user_count=GREATEST(COALESCE(active_user_count,0),#{count}),active_synced_at=#{at}
WHERE stat_date=#{date} AND (active_synced_at IS NULL OR active_synced_at<#{at})
""")
    int synchronizeActive(
            @Param("date") LocalDate date, @Param("count") long count, @Param("at") Instant at);

    @Delete("DELETE FROM activity_daily_summaries WHERE stat_date<#{before}")
    int deleteBefore(LocalDate before);
}
