package com.ginkgo.foodmap.notification.mapper;

import com.ginkgo.foodmap.notification.entity.NotificationEntity;

import org.apache.ibatis.annotations.*;

import java.util.List;

/** 每条用户查询和写入均携带收件人条件；不访问商户或用户模块的数据表。 */
@Mapper
public interface NotificationMapper {
    String COLUMNS =
            "id,recipient_user_id,notification_type,source_id,review_id,actor_user_id,request_id,request_type,decision,restaurant_name,reject_reason,created_at,read_at";
    // 只用于收件箱读取/已读；来源核验必须包含已删除记录，避免去重回执失效。
    String VISIBLE = " AND deleted_at IS NULL";
    // 分类枚举由绑定校验和服务共同限制，所有查询复用同一条件，禁止漏掉已读或计数过滤。
    String SCOPE =
            " AND (#{category}='ALL' OR (#{category}='SYSTEM' AND notification_type IN"
                    + " ('RESTAURANT_DECISION','ANNOUNCEMENT')) OR (#{category}='COMMENTS' AND"
                    + " notification_type='REVIEW_REPLY') OR (#{category}='FOLLOWERS' AND"
                    + " notification_type='USER_FOLLOWED') OR (#{category}='LIKES' AND"
                    + " notification_type IN ('REVIEW_LIKED','REPLY_LIKED')))";

    // 公告按读取时可见性合并；个人状态为空即未读，撤回或本人删除后整行排除。
    String INBOX =
            "(SELECT "
                    + COLUMNS
                    + ",NULL AS announcement_title FROM notification_messages WHERE"
                    + " recipient_user_id=#{owner}"
                    + VISIBLE
                    + " UNION ALL SELECT a.id,#{owner} AS recipient_user_id,'ANNOUNCEMENT' AS"
                    + " notification_type,a.id AS source_id,NULL AS review_id,NULL AS"
                    + " actor_user_id,NULL AS request_id,NULL AS request_type,NULL AS decision,NULL"
                    + " AS restaurant_name,NULL AS reject_reason,a.published_at AS"
                    + " created_at,s.read_at,a.title AS announcement_title FROM"
                    + " notification_announcements a LEFT JOIN"
                    + " notification_announcement_user_states s ON s.announcement_id=a.id AND"
                    + " s.user_id=#{owner} WHERE a.status='PUBLISHED' AND s.deleted_at IS NULL)"
                    + " inbox";

    /** 同一终态申请只有一条通知；无变化分支不覆盖已读状态与原始快照。 */
    @Insert(
            "INSERT INTO"
                + " notification_messages(recipient_user_id,notification_type,source_id,request_id,request_type,decision,restaurant_name,reject_reason)"
                + " VALUES(#{recipientUserId},'RESTAURANT_DECISION',#{requestId},#{requestId},#{requestType},#{decision},#{restaurantName},#{rejectReason})"
                + " ON DUPLICATE KEY UPDATE id=id")
    int insert(NotificationEntity row);

    /** 同回复仅一条通知，重试不覆盖首次阅读状态；正文和用户名都不作为历史快照保存。 */
    @Insert(
            "INSERT INTO"
                + " notification_messages(recipient_user_id,notification_type,source_id,review_id,actor_user_id)"
                + " VALUES(#{recipientUserId},'REVIEW_REPLY',#{sourceId},#{reviewId},#{actorUserId})"
                + " ON DUPLICATE KEY UPDATE id=id")
    int insertReply(NotificationEntity row);

    /** 首次点赞按永久关系ID去重，重新点赞不覆盖旧通知。 */
    @Insert(
            "INSERT INTO"
                + " notification_messages(recipient_user_id,notification_type,source_id,review_id,actor_user_id)"
                + " VALUES(#{recipientUserId},#{notificationType},#{sourceId},#{reviewId},#{actorUserId})"
                + " ON DUPLICATE KEY UPDATE id=id")
    int insertLike(NotificationEntity row);

    /** 首次关注只发送一次，取关重关保留旧通知与首次已读时间。 */
    @Insert(
            "INSERT INTO"
                + " notification_messages(recipient_user_id,notification_type,source_id,actor_user_id)"
                + " VALUES(#{recipientUserId},'USER_FOLLOWED',#{sourceId},#{actorUserId}) ON"
                + " DUPLICATE KEY UPDATE id=id")
    int insertFollow(NotificationEntity row);

    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM notification_messages WHERE recipient_user_id=#{owner} AND"
                    + " notification_type=#{type} AND source_id=#{source}")
    NotificationEntity bySource(
            @Param("owner") long owner, @Param("type") String type, @Param("source") long source);

    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM notification_messages WHERE recipient_user_id=#{owner} AND"
                    + " notification_type='REVIEW_REPLY' AND source_id=#{reply}")
    NotificationEntity byReply(@Param("owner") long owner, @Param("reply") long reply);

    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM notification_messages WHERE recipient_user_id=#{owner} AND"
                    + " request_id=#{request}")
    NotificationEntity byRequest(@Param("owner") long owner, @Param("request") long request);

    /** 在SQL中合并两类来源后统一分页，避免分别分页再拼接导致漏项。 */
    @Select(
            "SELECT * FROM "
                    + INBOX
                    + " WHERE recipient_user_id=#{owner}"
                    + SCOPE
                    + " ORDER BY created_at DESC,id DESC,notification_type DESC LIMIT #{limit}"
                    + " OFFSET #{offset}")
    List<NotificationEntity> page(
            @Param("owner") long owner,
            @Param("category") String category,
            @Param("offset") long offset,
            @Param("limit") int limit);

    // 计数分别走来源索引后相加，不为导航红点物化包含标题的混合列表。
    String ANNOUNCEMENT_COUNT =
            " FROM notification_announcements a LEFT JOIN notification_announcement_user_states s"
                + " ON s.announcement_id=a.id AND s.user_id=#{owner} WHERE a.status='PUBLISHED' AND"
                + " s.deleted_at IS NULL AND #{category} IN ('ALL','SYSTEM')";

    @Select(
            "SELECT (SELECT COUNT(*) FROM notification_messages WHERE recipient_user_id=#{owner}"
                    + VISIBLE
                    + SCOPE
                    + ") + (SELECT COUNT(*)"
                    + ANNOUNCEMENT_COUNT
                    + ")")
    long count(@Param("owner") long owner, @Param("category") String category);

    @Select(
            "SELECT (SELECT COUNT(*) FROM notification_messages WHERE recipient_user_id=#{owner}"
                + " AND read_at IS NULL"
                    + VISIBLE
                    + SCOPE
                    + ") + (SELECT COUNT(*)"
                    + ANNOUNCEMENT_COUNT
                    + " AND s.read_at IS NULL)")
    long unread(@Param("owner") long owner, @Param("category") String category);

    @Select(
            "SELECT * FROM "
                    + INBOX
                    + " WHERE notification_type IN ('RESTAURANT_DECISION','ANNOUNCEMENT') ORDER BY"
                    + " created_at DESC,id DESC,notification_type DESC LIMIT 1")
    NotificationEntity latestSystem(long owner);

    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM notification_messages WHERE recipient_user_id=#{owner} AND id=#{id}"
                    + VISIBLE
                    + " FOR UPDATE")
    NotificationEntity lock(@Param("owner") long owner, @Param("id") long id);

    /** 删除重试仍须核验收件人，已删除记录只作为内部回执读取，不能重新展示。 */
    @Select(
            "SELECT "
                    + COLUMNS
                    + " FROM notification_messages WHERE recipient_user_id=#{owner} AND id=#{id}"
                    + " FOR UPDATE")
    NotificationEntity lockForRemoval(@Param("owner") long owner, @Param("id") long id);

    /** 首次删除时间保持不变，不改变已读时间、来源事实或源业务内容。 */
    @Update(
            "UPDATE notification_messages SET deleted_at=GREATEST(CURRENT_TIMESTAMP(6),created_at)"
                    + " WHERE recipient_user_id=#{owner} AND id=#{id} AND deleted_at IS NULL")
    int remove(@Param("owner") long owner, @Param("id") long id);

    /** 首次已读时间保持不变，重复点击属于幂等操作。 */
    @Update(
            "UPDATE notification_messages SET read_at=GREATEST(CURRENT_TIMESTAMP(6),created_at)"
                    + " WHERE recipient_user_id=#{owner} AND id=#{id} AND read_at IS NULL"
                    + VISIBLE)
    int markRead(@Param("owner") long owner, @Param("id") long id);

    @Select(
            "SELECT COALESCE(MAX(id),0) FROM notification_messages WHERE recipient_user_id=#{owner}"
                    + VISIBLE)
    long latestId(long owner);

    /** 在同一RR快照内分批取初始可见的未读ID；不能只按MAX更新，否则稍后提交的较小ID也会被误读。 */
    @Select(
            "SELECT id FROM notification_messages WHERE recipient_user_id=#{owner} AND read_at IS"
                    + " NULL AND id>#{after} AND id<=#{through}"
                    + VISIBLE
                    + SCOPE
                    + " ORDER BY id LIMIT 500")
    List<Long> unreadIds(
            @Param("owner") long owner,
            @Param("category") String category,
            @Param("after") long after,
            @Param("through") long through);

    /** 仅更新快照已选中的有界ID集合；保留并发单条已读的首次时间，绝不扩大收件人范围。 */
    @Update(
            "<script>UPDATE notification_messages SET"
                    + " read_at=GREATEST(CURRENT_TIMESTAMP(6),created_at) WHERE"
                    + " recipient_user_id=#{owner} AND read_at IS NULL"
                    + VISIBLE
                    + " AND id IN <foreach collection='ids' item='id' open='(' separator=','"
                    + " close=')'>#{id}</foreach></script>")
    int markReadBatch(@Param("owner") long owner, @Param("ids") List<Long> ids);
}
