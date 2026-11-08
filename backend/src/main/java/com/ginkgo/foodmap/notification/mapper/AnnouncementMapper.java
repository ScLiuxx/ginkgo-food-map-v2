package com.ginkgo.foodmap.notification.mapper;

import com.ginkgo.foodmap.notification.entity.AnnouncementEntity;

import org.apache.ibatis.annotations.*;

import java.time.Instant;
import java.util.List;

/** 只访问notification自有表；公告主行锁统一串行化撤回及个人操作。 */
@Mapper
public interface AnnouncementMapper {
    String COLUMNS =
            "id,client_request_id,request_hash,title,content,status,published_by,published_at,withdrawn_by,withdrawn_at";

    @Insert(
            "INSERT INTO"
                + " notification_announcements(client_request_id,request_hash,title,content,published_by)"
                + " VALUES(#{clientRequestId},#{requestHash},#{title},#{content},#{publishedBy})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AnnouncementEntity row);

    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM notification_announcements WHERE published_by=#{owner} AND"
                    + " client_request_id=#{request}")
    AnnouncementEntity byRequest(@Param("owner") long owner, @Param("request") byte[] request);

    @Select("SELECT " + COLUMNS + " FROM notification_announcements WHERE id=#{id}")
    AnnouncementEntity find(long id);

    @Select("SELECT " + COLUMNS + " FROM notification_announcements WHERE id=#{id} FOR UPDATE")
    AnnouncementEntity lock(long id);

    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM notification_announcements WHERE (#{status}='ALL' OR status=#{status})"
                    + " ORDER BY published_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}")
    List<AnnouncementEntity> page(
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("limit") int limit);

    @Select(
            "SELECT COUNT(*) FROM notification_announcements WHERE (#{status}='ALL' OR"
                    + " status=#{status})")
    long count(String status);

    @Update(
            "UPDATE notification_announcements SET"
                + " status='WITHDRAWN',withdrawn_by=#{owner},withdrawn_at=GREATEST(CURRENT_TIMESTAMP(6),published_at)"
                + " WHERE id=#{id} AND status='PUBLISHED'")
    int withdraw(@Param("id") long id, @Param("owner") long owner);

    @Select(
            "SELECT deleted_at FROM notification_announcement_user_states WHERE user_id=#{owner}"
                    + " AND announcement_id=#{id} FOR UPDATE")
    Instant deleted(@Param("owner") long owner, @Param("id") long id);

    /** 主行锁内写入稀疏状态；重复操作保留首次时间，已删除项不能被批量已读复活。 */
    @Insert(
            "INSERT INTO notification_announcement_user_states(user_id,announcement_id,read_at)"
                    + " VALUES(#{owner},#{id},CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE"
                    + " read_at=IF(deleted_at IS"
                    + " NULL,COALESCE(read_at,GREATEST(CURRENT_TIMESTAMP(6),created_at)),read_at)")
    int read(@Param("owner") long owner, @Param("id") long id);

    @Insert(
            "INSERT INTO notification_announcement_user_states(user_id,announcement_id,deleted_at)"
                    + " VALUES(#{owner},#{id},CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE"
                    + " deleted_at=COALESCE(deleted_at,GREATEST(CURRENT_TIMESTAMP(6),created_at))")
    int delete(@Param("owner") long owner, @Param("id") long id);

    /** 普通一致性读固定全部已读的初始快照；不能仅按MAX编号直接更新。 */
    @Select(
            "SELECT a.id FROM notification_announcements a LEFT JOIN"
                    + " notification_announcement_user_states s ON s.announcement_id=a.id AND"
                    + " s.user_id=#{owner} WHERE a.status='PUBLISHED' AND s.deleted_at IS NULL AND"
                    + " s.read_at IS NULL AND a.id>#{after} ORDER BY a.id LIMIT 500")
    List<Long> unreadIds(@Param("owner") long owner, @Param("after") long after);

    @Select(
            "SELECT"
                + " a.id,a.client_request_id,a.request_hash,a.title,a.content,a.status,a.published_by,a.published_at,a.withdrawn_by,a.withdrawn_at"
                + " FROM notification_announcements a LEFT JOIN"
                + " notification_announcement_user_states s ON s.announcement_id=a.id AND"
                + " s.user_id=#{owner} WHERE a.id=#{id} AND a.status='PUBLISHED' AND s.deleted_at"
                + " IS NULL")
    AnnouncementEntity visible(@Param("owner") long owner, @Param("id") long id);
}
