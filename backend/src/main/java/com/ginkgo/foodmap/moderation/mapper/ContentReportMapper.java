package com.ginkgo.foodmap.moderation.mapper;

import com.ginkgo.foodmap.moderation.entity.ContentReportEntity;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 只访问moderation自有表；点评、回复和账号事实通过公开服务核验，不跨模块JOIN。 */
@Mapper
public interface ContentReportMapper {
    String COLUMNS =
            "id,reporter_user_id,target_type,target_id,target_author_user_id,review_id,"
                    + "content_snapshot,reason,detail,status,handled_by,handled_at,decision_note,"
                    + "created_at";

    /** 唯一键由数据库兜底，并发重复提交由服务捕获冲突后返回首条举报。 */
    @Insert(
            "INSERT INTO"
                + " content_reports(reporter_user_id,target_type,target_id,target_author_user_id,"
                + "review_id,content_snapshot,reason,detail)"
                + " VALUES(#{reporterUserId},#{targetType},#{targetId},#{targetAuthorUserId},"
                + "#{reviewId},#{contentSnapshot},#{reason},#{detail,jdbcType=VARCHAR})")
    int insert(ContentReportEntity row);

    /** 举报人只能读取自己的举报，不能借编号探测他人记录。 */
    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM content_reports WHERE reporter_user_id=#{reporter}"
                    + " AND target_type=#{type} AND target_id=#{target}")
    ContentReportEntity find(
            @Param("reporter") long reporter,
            @Param("type") String type,
            @Param("target") long target);
}
