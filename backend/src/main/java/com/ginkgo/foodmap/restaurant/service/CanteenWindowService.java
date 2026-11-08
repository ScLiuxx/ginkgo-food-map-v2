package com.ginkgo.foodmap.restaurant.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.common.exception.CommonErrorCode;
import com.ginkgo.foodmap.restaurant.dto.CanteenWindowRequest;
import com.ginkgo.foodmap.restaurant.dto.RestaurantReviewDelta;
import com.ginkgo.foodmap.restaurant.dto.RestaurantReviewTarget;
import com.ginkgo.foodmap.restaurant.entity.CanteenWindowEntity;
import com.ginkgo.foodmap.restaurant.exception.RestaurantErrorCode;
import com.ginkgo.foodmap.restaurant.mapper.CanteenWindowMapper;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantReviewStatisticsMapper;
import com.ginkgo.foodmap.restaurant.support.RestaurantIdentifiers;
import com.ginkgo.foodmap.restaurant.vo.CanteenWindowViews;
import com.ginkgo.foodmap.security.service.CurrentActorService;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.List;

/** 独立窗口身份、父食堂锁及真实统计；公开查询不创建统计，不生成示例资料。 */
@Service
@RequiredArgsConstructor
public class CanteenWindowService {
    private final CanteenWindowMapper windows;
    private final RestaurantReviewStatisticsMapper parents;
    private final RestaurantSearchOutboxService outbox;
    private final CurrentActorService actors;
    private final ObjectMapper json;

    /** 返回完整窗口集合供楼层分类筛选；管理端包含已删除项用于恢复。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<CanteenWindowViews.Item> list(String canteen, boolean management) {
        long id = RestaurantIdentifiers.id(canteen);
        if (management) {
            admin();
            if (!"CANTEEN".equals(windows.existingParentType(id))) {
                throw missing();
            }
        } else if (!"CANTEEN".equals(windows.parentType(id))) {
            throw missing();
        }
        return windows.list(id, management).stream().map(this::view).toList();
    }

    /** 停业窗口仍可浏览历史，父食堂或窗口删除后停止公开。 */
    @Transactional(readOnly = true)
    public CanteenWindowViews.Item detail(String value) {
        return view(visibleEntity(RestaurantIdentifiers.id(value)));
    }

    private CanteenWindowEntity visibleEntity(long id) {
        var row = windows.visible(id);
        if (row == null) {
            throw missing();
        }
        return row;
    }

    /** 管理员创建仅允许可见食堂；UUID与内容摘要防止网络重试重复录入。 */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CanteenWindowViews.Item create(String canteen, CanteenWindowRequest request) {
        long user = admin(), parent = RestaurantIdentifiers.id(canteen);
        lockParent(parent);
        var row = normalize(request);
        if (request.getClientRequestId() == null) {
            throw invalid();
        }
        row.setCanteenId(parent);
        row.setCreatedBy(user);
        var uuid = request.getClientRequestId();
        row.setClientRequestId(
                ByteBuffer.allocate(16)
                        .putLong(uuid.getMostSignificantBits())
                        .putLong(uuid.getLeastSignificantBits())
                        .array());
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(8).putLong(parent).array());
            row.setRequestHash(digest.digest(json.writeValueAsBytes(request)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try {
            windows.insert(row);
        } catch (DuplicateKeyException e) {
            var old = windows.request(user, row.getClientRequestId());
            if (old == null) {
                throw e;
            }
            if (!MessageDigest.isEqual(old.getRequestHash(), row.getRequestHash())) {
                throw new BusinessException(CommonErrorCode.DATA_CONFLICT);
            }
            var result = view(old);
            result.setReplayed(true);
            return result;
        }
        windows.initialize(row.getId());
        refresh(parent);
        return view(windows.find(row.getId()));
    }

    /** 不允许改所属食堂；更换经营主体应关闭旧窗口另建，原点评不搬家。 */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CanteenWindowViews.Item update(String id, CanteenWindowRequest input) {
        long user = admin();
        var old = lockEntity(RestaurantIdentifiers.id(id), false);
        var row = normalize(input);
        row.setId(old.getId());
        row.setUpdatedBy(user);
        windows.update(row);
        refresh(old.getCanteenId());
        return view(windows.find(old.getId()));
    }

    /** 删除和恢复幂等，不物理删除窗口或迁移点评；父食堂必须先恢复。 */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deleted(String id, boolean deleted) {
        long user = admin(), key = RestaurantIdentifiers.id(id);
        var row = windows.find(key);
        if (row == null) {
            throw missing();
        }
        lockParent(row.getCanteenId());
        windows.lock(key);
        if (windows.deleted(key, user, deleted) > 0) {
            refresh(row.getCanteenId());
        }
    }

    /** 点评和互动入口共用父食堂→窗口锁顺序，发布时额外检查营业状态。 */
    private CanteenWindowEntity lockEntity(long id, boolean publishing) {
        var row = windows.find(id);
        if (row == null) {
            throw missing();
        }
        lockParent(row.getCanteenId());
        windows.lock(id);
        row = visibleEntity(id);
        if (publishing && !"OPERATING".equals(row.getBusinessStatus())) {
            throw new BusinessException(RestaurantErrorCode.STATE_CONFLICT);
        }
        windows.initialize(id);
        return row;
    }

    /** 窗口累计分、食堂等权分、索引版本及Outbox与点评同事务更新。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyDelta(long id, RestaurantReviewDelta delta) {
        var row = visibleEntity(id);
        if (windows.delta(id, delta) != 1) {
            throw new IllegalStateException("Missing window statistics");
        }
        refresh(row.getCanteenId());
    }

    /** 旧食堂点评删除不应重新混入食堂窗口汇总。调用方已持父锁。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refresh(long parent) {
        windows.refresh(parent);
        if (parents.advanceSearchProjectionVersion(parent) != 1) {
            throw new IllegalStateException("Missing canteen projection");
        }
        outbox.record(parent);
    }

    /** 其他业务模块只核验可见性，不取得restaurant持久化Entity。 */
    public void requireVisible(long id) {
        visibleEntity(id);
    }

    /** 返回必要父食堂逻辑ID供可信协作，不暴露管理元数据。 */
    public long canteenId(long id) {
        return visibleEntity(id).getCanteenId();
    }

    /** 可信业务事务的父层锁入口，不把私有实体传给其他模块。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockVisible(long id, boolean publishing) {
        lockEntity(id, publishing);
    }

    /** 本人点评与点赞批量查询可见窗口，避免逐条访问父资源。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public java.util.Map<Long, RestaurantReviewTarget> targets(List<Long> ids) {
        var valid = ids.stream().filter(id -> id != null && id > 0).distinct().toList();
        var result = new java.util.LinkedHashMap<Long, RestaurantReviewTarget>();
        for (int start = 0; start < valid.size(); start += 500) {
            for (var row :
                    windows.visibleBatch(
                            valid.subList(start, Math.min(start + 500, valid.size())))) {
                var item = new RestaurantReviewTarget();
                item.setId(row.getId().toString());
                item.setWindowId(row.getId().toString());
                item.setCanteenId(row.getCanteenId().toString());
                item.setTargetType("WINDOW");
                item.setName(row.getName());
                item.setBusinessStatus(row.getBusinessStatus());
                result.put(row.getId(), item);
            }
        }
        return result;
    }

    private void lockParent(long id) {
        if (parents.lockRestaurant(id) == null || !"CANTEEN".equals(windows.parentType(id))) {
            throw missing();
        }
    }

    private long admin() {
        var actor = actors.requireActor();
        if (!actor.isAdministrator()) {
            throw new AccessDeniedException("需要管理员权限");
        }
        return actor.getUserId();
    }

    private BusinessException missing() {
        return new BusinessException(RestaurantErrorCode.NOT_FOUND);
    }

    private BusinessException invalid() {
        return new BusinessException(RestaurantErrorCode.INVALID_INPUT);
    }

    private CanteenWindowEntity normalize(CanteenWindowRequest input) {
        if (input == null
                || input.getName() == null
                || input.getName().isBlank()
                || input.getName().strip().codePointCount(0, input.getName().strip().length()) > 40
                || input.getFloorNumber() == null
                || input.getFloorNumber() < -10
                || input.getFloorNumber() > 100
                || input.getPriceKind() == null
                || input.getBusinessStatus() == null
                || !java.util.Set.of("UNKNOWN", "EXACT", "ESTIMATE").contains(input.getPriceKind())
                || !java.util.Set.of("OPERATING", "CLOSED").contains(input.getBusinessStatus())) {
            throw invalid();
        }
        input.setName(input.getName().strip());
        if (input.getDescription() != null) {
            input.setDescription(input.getDescription().strip());
            if (input.getDescription().isEmpty()) {
                input.setDescription(null);
            } else if (input.getDescription().codePointCount(0, input.getDescription().length())
                    > 500) {
                throw invalid();
            }
        }
        if ("UNKNOWN".equals(input.getPriceKind())
                ? input.getPricePerPerson() != null
                : input.getPricePerPerson() == null
                        || input.getPricePerPerson() < 1
                        || input.getPricePerPerson() > 5000) {
            throw invalid();
        }
        var row = new CanteenWindowEntity();
        BeanUtils.copyProperties(input, row);
        return row;
    }

    private CanteenWindowViews.Item view(CanteenWindowEntity row) {
        var result = new CanteenWindowViews.Item();
        BeanUtils.copyProperties(row, result);
        result.setId(row.getId().toString());
        result.setCanteenId(row.getCanteenId().toString());
        result.setDeleted(row.getDeletedAt() != null);
        if (row.getCoverFileId() != null && row.getDeletedAt() == null) {
            result.setCoverUrl(
                    "/api/canteen-windows/" + row.getId() + "/photos/" + row.getCoverFileId());
        }
        return result;
    }
}
