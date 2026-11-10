package com.ginkgo.foodmap.moderation.mapper;

import com.ginkgo.foodmap.moderation.entity.ContentReportEntity;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

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

    /** 管理端按状态分页，先到先处理，同时间按自增ID稳定排序。 */
    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM content_reports WHERE status=#{status}"
                    + " ORDER BY created_at,id LIMIT #{size} OFFSET #{offset}")
    List<ContentReportEntity> pageByStatus(
            @Param("status") String status, @Param("offset") long offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM content_reports WHERE status=#{status}")
    long countByStatus(@Param("status") String status);

    @Select("SELECT " + COLUMNS + " FROM content_reports WHERE id=#{id}")
    ContentReportEntity findById(@Param("id") long id);

    /**
     * 同一内容的所有待处理举报一并驳回（RISK-026）。
     *
     * <p>只更新PENDING行，重复驳回或并发处理返回0，不会覆盖已删除内容的终态。
     */
    @Update(
            "UPDATE content_reports SET status='DISMISSED',handled_by=#{handler},"
                    + "handled_at=#{handledAt},decision_note=#{note,jdbcType=VARCHAR}"
                    + " WHERE target_type=#{type} AND target_id=#{target} AND status='PENDING'")
    int dismissPendingTarget(
            @Param("type") String type,
            @Param("target") long target,
            @Param("handler") long handler,
            @Param("handledAt") Instant handledAt,
            @Param("note") String note);

    /**
     * 内容永久删除后，把同一内容下所有待处理举报结案（RISK-026）。
     *
     * <p>只更新PENDING行，已驳回或已结案的举报保持不变；并发删除时后到事务返回0。
     */
    @Update(
            "UPDATE content_reports SET status='RESOLVED',handled_by=#{handler},"
                    + "handled_at=#{handledAt},decision_note=#{note,jdbcType=VARCHAR}"
                    + " WHERE target_type=#{type} AND target_id=#{target} AND status='PENDING'")
    int resolvePendingTarget(
            @Param("type") String type,
            @Param("target") long target,
            @Param("handler") long handler,
            @Param("handledAt") Instant handledAt,
            @Param("note") String note);
}
