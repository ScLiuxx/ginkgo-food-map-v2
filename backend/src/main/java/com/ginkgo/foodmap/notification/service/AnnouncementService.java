package com.ginkgo.foodmap.notification.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.common.exception.CommonErrorCode;
import com.ginkgo.foodmap.notification.dto.AnnouncementPublishRequest;
import com.ginkgo.foodmap.notification.dto.AnnouncementQuery;
import com.ginkgo.foodmap.notification.entity.AnnouncementEntity;
import com.ginkgo.foodmap.notification.exception.NotificationErrorCode;
import com.ginkgo.foodmap.notification.mapper.AnnouncementMapper;
import com.ginkgo.foodmap.notification.vo.AnnouncementViews;
import com.ginkgo.foodmap.security.service.CurrentActorService;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 公告生命周期服务：发布不可编辑，撤回与用户操作共用主行锁，不扇出收件记录。 */
@Service
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class AnnouncementService {
    private final AnnouncementMapper announcements;
    private final CurrentActorService actors;
    private final NotificationChangeService changes;

    /** 使用发布人+UUID去重；唯一键竞争结束后核验摘要，失败整笔回滚。 */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AnnouncementViews.Detail publish(AnnouncementPublishRequest request) {
        long owner = admin();
        if (request == null || request.getClientRequestId() == null) {
            throw invalid();
        }
        String title = normalize(request.getTitle(), 120),
                content = normalize(request.getContent(), 10000);
        var uuid = request.getClientRequestId();
        byte[] key =
                ByteBuffer.allocate(16)
                        .putLong(uuid.getMostSignificantBits())
                        .putLong(uuid.getLeastSignificantBits())
                        .array();
        byte[] hash = hash(title, content);
        var row = new AnnouncementEntity();
        row.setClientRequestId(key);
        row.setRequestHash(hash);
        row.setTitle(title);
        row.setContent(content);
        row.setPublishedBy(owner);
        try {
            announcements.insert(row);
        } catch (DuplicateKeyException duplicate) {
            var existing = announcements.byRequest(owner, key);
            if (existing == null) {
                throw duplicate;
            }
            if (!MessageDigest.isEqual(existing.getRequestHash(), hash)) {
                throw new BusinessException(CommonErrorCode.DATA_CONFLICT);
            }
            return view(existing, true);
        }
        changes.allChanged();
        return view(announcements.find(row.getId()), false);
    }

    /** 管理端可核对撤回记录，用户侧始终通过独立可见性查询。 */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AnnouncementViews.Page page(AnnouncementQuery query) {
        admin();
        var result = new AnnouncementViews.Page();
        result.setTotal(announcements.count(query.getStatus()));
        result.setPage(query.getPage());
        result.setPageSize(query.getPageSize());
        result.setTotalPages((result.getTotal() + query.getPageSize() - 1) / query.getPageSize());
        result.setRecords(
                announcements
                        .page(
                                query.getStatus(),
                                ((long) query.getPage() - 1) * query.getPageSize(),
                                query.getPageSize())
                        .stream()
                        .map(row -> view(row, false))
                        .toList());
        return result;
    }

    /** 管理员读取完整历史，不提供正文修改接口。 */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public AnnouncementViews.Detail adminDetail(String id) {
        admin();
        var row = announcements.find(key(id));
        if (row == null) {
            throw missing();
        }
        return view(row, false);
    }

    /** 首次撤回人及时间永久保留；其他管理员重复撤回也返回同一终态。 */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AnnouncementViews.Detail withdraw(String id) {
        long owner = admin();
        long key = key(id);
        var row = announcements.lock(key);
        if (row == null) {
            throw missing();
        }
        if ("WITHDRAWN".equals(row.getStatus())) {
            return view(row, true);
        }
        announcements.withdraw(key, owner);
        changes.allChanged();
        return view(announcements.find(key), false);
    }

    /** GET不标记已读；删除或撤回后不再暴露公告正文。 */
    @Transactional(readOnly = true)
    public AnnouncementViews.Detail detail(String id) {
        long owner = actors.requireActor().getUserId();
        var row = announcements.visible(owner, key(id));
        if (row == null) {
            throw missing();
        }
        return view(row, false);
    }

    /** 单条已读与撤回共用主行锁，保留首次已读；用户不可指定他人身份。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void read(String id) {
        long owner = actors.requireActor().getUserId(), key = key(id);
        var row = announcements.lock(key);
        requirePublished(row);
        if (announcements.deleted(owner, key) != null) {
            throw missing();
        }
        announcements.read(owner, key);
        changes.changed(owner);
    }

    /** 本人重复删除成功；删除回执在公告撤回后仍可安全重试，不重建可见内容。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(String id) {
        long owner = actors.requireActor().getUserId(), key = key(id);
        var row = announcements.lock(key);
        if (row == null) {
            throw missing();
        }
        if (announcements.deleted(owner, key) != null) {
            return;
        }
        requirePublished(row);
        announcements.delete(owner, key);
        changes.changed(owner);
    }

    /** 参与收件箱RR事务，按初始快照分页取ID，再按升序锁行复核撤回和删除。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void readAll(long owner) {
        if (actors.requireActor().getUserId() != owner) {
            throw new AccessDeniedException("仅能处理本人公告");
        }
        long after = 0;
        while (true) {
            var ids = announcements.unreadIds(owner, after);
            if (ids.isEmpty()) {
                break;
            }
            for (long id : ids) {
                var row = announcements.lock(id);
                if (row != null
                        && "PUBLISHED".equals(row.getStatus())
                        && announcements.deleted(owner, id) == null) {
                    announcements.read(owner, id);
                    changes.changed(owner);
                }
            }
            after = ids.getLast();
        }
    }

    private long admin() {
        var actor = actors.requireActor();
        if (!actor.isAdministrator()) {
            throw new AccessDeniedException("需要管理员权限");
        }
        return actor.getUserId();
    }

    private void requirePublished(AnnouncementEntity row) {
        if (row == null || !"PUBLISHED".equals(row.getStatus())) {
            throw missing();
        }
    }

    private BusinessException missing() {
        return new BusinessException(NotificationErrorCode.NOT_FOUND);
    }

    private BusinessException invalid() {
        return new BusinessException(CommonErrorCode.VALIDATION_FAILED);
    }

    private long key(String id) {
        if (id == null
                || !id.matches("[1-9][0-9]{0,18}")
                || new java.math.BigInteger(id).bitLength() > 63) {
            throw invalid();
        }
        return Long.parseLong(id);
    }

    private String normalize(String value, int limit) {
        if (value == null) {
            throw invalid();
        }
        String result = value.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (result.isBlank() || result.codePointCount(0, result.length()) > limit) {
            throw invalid();
        }
        return result;
    }

    private byte[] hash(String title, String content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] first = title.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(first.length).array());
            digest.update(first);
            return digest.digest(content.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private AnnouncementViews.Detail view(AnnouncementEntity row, boolean replayed) {
        var result = new AnnouncementViews.Detail();
        result.setId(row.getId().toString());
        result.setTitle(row.getTitle());
        result.setContent(row.getContent());
        result.setStatus(row.getStatus());
        result.setPublishedBy(row.getPublishedBy().toString());
        result.setPublishedAt(row.getPublishedAt());
        result.setWithdrawnBy(
                row.getWithdrawnBy() == null ? null : row.getWithdrawnBy().toString());
        result.setWithdrawnAt(row.getWithdrawnAt());
        result.setReplayed(replayed);
        return result;
    }
}
