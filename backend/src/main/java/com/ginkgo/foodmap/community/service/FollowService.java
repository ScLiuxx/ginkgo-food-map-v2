package com.ginkgo.foodmap.community.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.community.dto.FollowListQuery;
import com.ginkgo.foodmap.community.entity.FollowEntity;
import com.ginkgo.foodmap.community.exception.FollowErrorCode;
import com.ginkgo.foodmap.community.mapper.FollowMapper;
import com.ginkgo.foodmap.community.vo.FollowViews;
import com.ginkgo.foodmap.notification.dto.FollowNotification;
import com.ginkgo.foodmap.notification.service.NotificationPublicationService;
import com.ginkgo.foodmap.user.dto.PublicAuthor;
import com.ginkgo.foodmap.user.service.FollowUserService;
import com.ginkgo.foodmap.user.service.PublicAuthorService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

/** 关注应用流程：用户锁、关系和首次通知在同一事务中处理，人数直接读取有效关系。 */
@Service
@RequiredArgsConstructor
public class FollowService {
    private static final String FOLLOWING = "FOLLOWING";
    private static final String FOLLOWERS = "FOLLOWERS";

    private final FollowMapper followMapper;
    private final FollowUserService followUsers;
    private final PublicAuthorService publicAuthors;
    private final NotificationPublicationService notifications;

    /** 人数向登录用户展示，读取前只校验目标用户存在，不要求其名单已公开。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FollowViews.Summary summary(long viewerId, long targetUserId) {
        followUsers.privacy(targetUserId);
        return readSummary(viewerId, targetUserId);
    }

    /** 用户锁始终按ID升序取得；只有首次创建关系才通知，重复提交不更新时间。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FollowViews.Summary set(long actorId, long targetUserId, boolean active) {
        if (actorId == targetUserId) {
            throw new BusinessException(FollowErrorCode.SELF);
        }

        followUsers.lockForFollow(actorId, targetUserId, active);
        FollowEntity relationship = followMapper.lock(actorId, targetUserId);
        if (relationship == null && active) {
            createRelationshipAndNotify(actorId, targetUserId);
        } else if (relationship != null && relationship.isActive() != active) {
            if (followMapper.set(relationship.getId(), active) != 1) {
                throw new IllegalStateException("Follow state changed");
            }
        }

        return readSummary(actorId, targetUserId);
    }

    /** 名单授权、计数、分页及成员补全共享读取快照，不在分页后过滤或逐个查用户。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FollowViews.Page list(
            long viewerId, long listOwnerId, String kind, FollowListQuery query) {
        requireListAccess(viewerId, listOwnerId, kind);
        long total = followMapper.count(listOwnerId, kind);
        FollowViews.Page page = createPage(listOwnerId, kind, total, query);
        if (query.getOffset() >= total) {
            return page;
        }

        var memberIds =
                followMapper.page(listOwnerId, kind, query.getPageSize(), query.getOffset());
        if (memberIds.isEmpty()) {
            return page;
        }

        var authorsById = publicAuthors.find(memberIds);
        Set<Long> followedUserIds = new HashSet<>(followMapper.followingIds(viewerId, memberIds));
        page.setRecords(
                memberIds.stream()
                        .map(
                                memberId ->
                                        toMember(
                                                memberId,
                                                authorsById.get(Long.toString(memberId)),
                                                followedUserIds))
                        .toList());
        return page;
    }

    /** 私有辅助方法沿用外层写事务，通知失败会同时回滚关系插入。 */
    private void createRelationshipAndNotify(long followerId, long followedId) {
        FollowEntity relationship = new FollowEntity();
        relationship.setFollowerUserId(followerId);
        relationship.setFollowedUserId(followedId);
        if (followMapper.insert(relationship) != 1) {
            throw new IllegalStateException("Follow insert failed");
        }

        FollowNotification notification = new FollowNotification();
        notification.setActorUserId(followerId);
        notification.setRecipientUserId(followedId);
        notification.setRelationId(relationship.getId());
        notifications.follow(notification);
    }

    private void requireListAccess(long viewerId, long listOwnerId, String kind) {
        var privacy = followUsers.privacy(listOwnerId);
        boolean listPublic =
                FOLLOWING.equals(kind) ? privacy.isFollowingPublic() : privacy.isFollowersPublic();
        if (viewerId != listOwnerId && !listPublic) {
            throw new BusinessException(FollowErrorCode.PRIVATE);
        }
    }

    private FollowViews.Page createPage(
            long ownerId, String kind, long total, FollowListQuery query) {
        FollowViews.Page page = new FollowViews.Page();
        page.setUserId(Long.toString(ownerId));
        page.setKind(kind);
        page.setTotal(total);
        page.setPage(query.getPage());
        page.setPageSize(query.getPageSize());
        page.setTotalPages(
                total / query.getPageSize() + (total % query.getPageSize() == 0 ? 0 : 1));
        return page;
    }

    private FollowViews.Member toMember(
            long memberId, PublicAuthor author, Set<Long> followedUserIds) {
        FollowViews.Member member = new FollowViews.Member();
        member.setUserId(Long.toString(memberId));
        member.setUsername(author == null ? "用户不可用" : author.getUsername());
        member.setLevel(author == null ? null : author.getLevel());
        member.setFollowing(followedUserIds.contains(memberId));
        return member;
    }

    private FollowViews.Summary readSummary(long viewerId, long targetUserId) {
        FollowViews.Summary summary = new FollowViews.Summary();
        summary.setUserId(Long.toString(targetUserId));
        summary.setFollowingCount(followMapper.count(targetUserId, FOLLOWING));
        summary.setFollowersCount(followMapper.count(targetUserId, FOLLOWERS));
        summary.setFollowing(
                viewerId != targetUserId && followMapper.following(viewerId, targetUserId));
        return summary;
    }
}
