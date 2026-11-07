package com.ginkgo.foodmap.notification.mapper;

import com.ginkgo.foodmap.notification.entity.NotificationEntity;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** 每条用户查询和写入均携带收件人条件；不访问商户或用户模块的数据表。 */
@Mapper
public interface NotificationMapper {
    String COLUMNS = "id,recipient_user_id,notification_type,source_id,review_id,actor_user_id,request_id,request_type,decision,restaurant_name,reject_reason,created_at,read_at";
    // 分类枚举由绑定校验和服务共同限制，所有查询复用同一条件，禁止漏掉已读或计数过滤。
    String SCOPE = " AND (#{category}='ALL' OR (#{category}='SYSTEM' AND notification_type='RESTAURANT_DECISION') OR (#{category}='COMMENTS' AND notification_type='REVIEW_REPLY') OR (#{category}='FOLLOWERS' AND notification_type='USER_FOLLOWED') OR (#{category}='LIKES' AND notification_type IN ('REVIEW_LIKED','REPLY_LIKED')))";

    /** 同一终态申请只有一条通知；无变化分支不覆盖已读状态与原始快照。 */
    @Insert("INSERT INTO notification_messages(recipient_user_id,notification_type,source_id,request_id,request_type,decision,restaurant_name,reject_reason) "
            + "VALUES(#{recipientUserId},'RESTAURANT_DECISION',#{requestId},#{requestId},#{requestType},#{decision},#{restaurantName},#{rejectReason}) ON DUPLICATE KEY UPDATE id=id")
    int insert(NotificationEntity row);

    /** 同回复仅一条通知，重试不覆盖首次阅读状态；正文和用户名都不作为历史快照保存。 */
    @Insert("INSERT INTO notification_messages(recipient_user_id,notification_type,source_id,review_id,actor_user_id) VALUES(#{recipientUserId},'REVIEW_REPLY',#{sourceId},#{reviewId},#{actorUserId}) ON DUPLICATE KEY UPDATE id=id")
    int insertReply(NotificationEntity row);

    /** 首次点赞按永久关系ID去重，重新点赞不覆盖旧通知。 */
    @Insert("INSERT INTO notification_messages(recipient_user_id,notification_type,source_id,review_id,actor_user_id) VALUES(#{recipientUserId},#{notificationType},#{sourceId},#{reviewId},#{actorUserId}) ON DUPLICATE KEY UPDATE id=id")
    int insertLike(NotificationEntity row);
    /** 首次关注只发送一次，取关重关保留旧通知与首次已读时间。 */
    @Insert("INSERT INTO notification_messages(recipient_user_id,notification_type,source_id,actor_user_id) VALUES(#{recipientUserId},'USER_FOLLOWED',#{sourceId},#{actorUserId}) ON DUPLICATE KEY UPDATE id=id")
    int insertFollow(NotificationEntity row);
    @Select("SELECT " + COLUMNS + " FROM notification_messages WHERE recipient_user_id=#{owner} AND notification_type=#{type} AND source_id=#{source}")
    NotificationEntity bySource(@Param("owner") long owner,@Param("type") String type,@Param("source") long source);

    @Select("SELECT " + COLUMNS + " FROM notification_messages WHERE recipient_user_id=#{owner} AND notification_type='REVIEW_REPLY' AND source_id=#{reply}")
    NotificationEntity byReply(@Param("owner") long owner,@Param("reply") long reply);

    @Select("SELECT " + COLUMNS + " FROM notification_messages WHERE recipient_user_id=#{owner} AND request_id=#{request}")
    NotificationEntity byRequest(@Param("owner") long owner, @Param("request") long request);

    @Select("SELECT " + COLUMNS + " FROM notification_messages WHERE recipient_user_id=#{owner}" + SCOPE + " ORDER BY created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}")
    List<NotificationEntity> page(@Param("owner") long owner, @Param("category") String category,@Param("offset") long offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM notification_messages WHERE recipient_user_id=#{owner}" + SCOPE)
    long count(@Param("owner") long owner,@Param("category") String category);

    @Select("SELECT COUNT(*) FROM notification_messages WHERE recipient_user_id=#{owner} AND read_at IS NULL" + SCOPE)
    long unread(@Param("owner") long owner,@Param("category") String category);

    @Select("SELECT " + COLUMNS + " FROM notification_messages WHERE recipient_user_id=#{owner} AND notification_type='RESTAURANT_DECISION' ORDER BY created_at DESC,id DESC LIMIT 1")
    NotificationEntity latestSystem(long owner);

    @Select("SELECT " + COLUMNS + " FROM notification_messages WHERE recipient_user_id=#{owner} AND id=#{id} FOR UPDATE")
    NotificationEntity lock(@Param("owner") long owner, @Param("id") long id);

    /** 首次已读时间保持不变，重复点击属于幂等操作。 */
    @Update("UPDATE notification_messages SET read_at=GREATEST(CURRENT_TIMESTAMP(6),created_at) WHERE recipient_user_id=#{owner} AND id=#{id} AND read_at IS NULL")
    int markRead(@Param("owner") long owner, @Param("id") long id);

    @Select("SELECT COALESCE(MAX(id),0) FROM notification_messages WHERE recipient_user_id=#{owner}")
    long latestId(long owner);

    /** 在同一RR快照内分批取初始可见的未读ID；不能只按MAX更新，否则稍后提交的较小ID也会被误读。 */
    @Select("SELECT id FROM notification_messages WHERE recipient_user_id=#{owner} AND read_at IS NULL AND id>#{after} AND id<=#{through}" + SCOPE + " ORDER BY id LIMIT 500")
    List<Long> unreadIds(@Param("owner") long owner,@Param("category") String category,@Param("after") long after,@Param("through") long through);

    /** 仅更新快照已选中的有界ID集合；保留并发单条已读的首次时间，绝不扩大收件人范围。 */
    @Update("<script>UPDATE notification_messages SET read_at=GREATEST(CURRENT_TIMESTAMP(6),created_at) WHERE recipient_user_id=#{owner} AND read_at IS NULL AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int markReadBatch(@Param("owner") long owner,@Param("ids") List<Long> ids);
}
