package com.ginkgo.foodmap.notification.service;

import com.ginkgo.foodmap.common.exception.*;
import com.ginkgo.foodmap.notification.dto.NotificationQuery;
import com.ginkgo.foodmap.notification.entity.NotificationEntity;
import com.ginkgo.foodmap.notification.exception.NotificationErrorCode;
import com.ginkgo.foodmap.notification.mapper.NotificationMapper;
import com.ginkgo.foodmap.notification.vo.NotificationViews;
import com.ginkgo.foodmap.security.service.CurrentActorService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 私有收件箱操作只从认证上下文取本人；管理员身份不会扩大消息读取或已读范围。 */
@Service
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NotificationInboxService {
    /** 服务端从当前身份取收件人，轻量查询仅返回总数。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public java.util.Map<String, Object> unread() {
        long owner = actors.requireActor().getUserId();
        return java.util.Map.of(
                "userId", Long.toString(owner), "unreadCount", messages.unread(owner, "ALL"));
    }

    private final CurrentActorService actors;
    private final NotificationMapper messages;
    private final AnnouncementService announcements;
    private final NotificationChangeService changes;
    private final com.ginkgo.foodmap.user.service.PublicAuthorService authors;

    /** 分类总数、未读数、系统摘要和本页使用同一快照；GET绝不自动标记已读。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NotificationViews.Page page(NotificationQuery query) {
        long owner = actors.requireActor().getUserId();
        var result = new NotificationViews.Page();
        result.setUserId(Long.toString(owner));
        result.setCategory(query.getCategory());
        result.setTotal(messages.count(owner, query.getCategory()));
        result.setUnreadCount(messages.unread(owner, query.getCategory()));
        result.setUnreadCounts(
                java.util.Map.of(
                        "SYSTEM",
                        messages.unread(owner, "SYSTEM"),
                        "COMMENTS",
                        messages.unread(owner, "COMMENTS"),
                        "LIKES",
                        messages.unread(owner, "LIKES"),
                        "FOLLOWERS",
                        messages.unread(owner, "FOLLOWERS")));
        var latest = messages.latestSystem(owner);
        result.setLatestSystem(latest == null ? null : item(latest));
        result.setPage(query.getPage());
        result.setPageSize(query.getPageSize());
        result.setTotalPages((result.getTotal() + query.getPageSize() - 1) / query.getPageSize());
        var rows =
                messages.page(
                        owner,
                        query.getCategory(),
                        ((long) query.getPage() - 1) * query.getPageSize(),
                        query.getPageSize());
        var actorIds =
                rows.stream()
                        .map(NotificationEntity::getActorUserId)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList();
        var names =
                actorIds.isEmpty()
                        ? java.util.Map.<String, com.ginkgo.foodmap.user.dto.PublicAuthor>of()
                        : authors.find(actorIds);
        result.setRecords(
                rows.stream()
                        .map(
                                row -> {
                                    var view = item(row);
                                    if (row.getActorUserId() != null) {
                                        var name = names.get(row.getActorUserId().toString());
                                        view.setActorUsername(
                                                name == null ? "用户不可用" : name.getUsername());
                                    }
                                    return view;
                                })
                        .toList());
        return result;
    }

    /** 不存在与越权统一404；锁定该条消息后幂等更新，不向调用者泄露其他收件人的记录。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void markRead(String id) {
        if (id != null && id.startsWith("announcement:")) {
            announcements.read(id.substring("announcement:".length()));
            return;
        }
        long owner = actors.requireActor().getUserId();
        long key = notificationId(id);
        if (messages.lock(owner, key) == null) {
            throw new BusinessException(NotificationErrorCode.NOT_FOUND);
        }
        messages.markRead(owner, key);
        changes.changed(owner);
    }

    /** 任意已读/未读消息均可删除，原收件人重试204；其他账号与未知编号统一404。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void remove(String id) {
        if (id != null && id.startsWith("announcement:")) {
            announcements.delete(id.substring("announcement:".length()));
            return;
        }
        long owner = actors.requireActor().getUserId();
        long key = notificationId(id);
        // 同一行锁串行化删除与单条已读；保留来源唯一键，不调用源业务服务。
        if (messages.lockForRemoval(owner, key) == null) {
            throw new BusinessException(NotificationErrorCode.NOT_FOUND);
        }
        messages.remove(owner, key);
        changes.changed(owner);
    }

    private long notificationId(String id) {
        if (id == null
                || !id.matches("[1-9][0-9]{0,18}")
                || new java.math.BigInteger(id).bitLength() > 63) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        return Long.parseLong(id);
    }

    /** 固定RR快照后分批更新当时可见的消息；覆盖较小ID迟提交场景，且不把全部ID一次性载入内存。 */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void markAllRead(String category) {
        long owner = actors.requireActor().getUserId();
        long through = messages.latestId(owner), after = 0;
        while (true) {
            var ids = messages.unreadIds(owner, category, after, through);
            if (ids.isEmpty()) {
                break;
            }
            messages.markReadBatch(owner, ids);
            changes.changed(owner);
            after = ids.getLast();
        }
        if ("ALL".equals(category) || "SYSTEM".equals(category)) {
            announcements.readAll(owner);
        }
    }

    private NotificationViews.Item item(NotificationEntity row) {
        var item = new NotificationViews.Item();
        boolean announcement = "ANNOUNCEMENT".equals(row.getNotificationType());
        item.setId((announcement ? "announcement:" : "") + row.getId());
        item.setAnnouncementId(announcement ? row.getId().toString() : null);
        item.setAnnouncementTitle(row.getAnnouncementTitle());
        item.setRequestId(row.getRequestId() == null ? null : row.getRequestId().toString());
        item.setSourceId(row.getSourceId().toString());
        item.setNotificationType(row.getNotificationType());
        item.setCategory(
                "USER_FOLLOWED".equals(row.getNotificationType())
                        ? "FOLLOWERS"
                        : "REVIEW_REPLY".equals(row.getNotificationType())
                                ? "COMMENTS"
                                : ("RESTAURANT_DECISION".equals(row.getNotificationType())
                                                || announcement)
                                        ? "SYSTEM"
                                        : "LIKES");
        item.setReviewId(row.getReviewId() == null ? null : row.getReviewId().toString());
        item.setActorUserId(row.getActorUserId() == null ? null : row.getActorUserId().toString());
        item.setRequestType(row.getRequestType());
        item.setDecision(row.getDecision());
        item.setRestaurantName(row.getRestaurantName());
        item.setRejectReason(row.getRejectReason());
        item.setCreatedAt(row.getCreatedAt());
        item.setReadAt(row.getReadAt());
        return item;
    }
}
