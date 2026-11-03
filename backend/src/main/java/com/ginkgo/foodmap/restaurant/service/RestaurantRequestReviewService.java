package com.ginkgo.foodmap.restaurant.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.restaurant.dto.RestaurantSaveRequest;
import com.ginkgo.foodmap.restaurant.dto.RestaurantApproveRequest;
import com.ginkgo.foodmap.restaurant.entity.RestaurantChangeEntity;
import com.ginkgo.foodmap.restaurant.entity.RestaurantPhotoEntity;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantPhotoMapper;
import com.ginkgo.foodmap.file.service.MediaFileService;
import com.ginkgo.foodmap.restaurant.exception.RestaurantErrorCode;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantRequestReviewMapper;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantWriteMapper;
import com.ginkgo.foodmap.restaurant.support.RestaurantIdentifiers;
import com.ginkgo.foodmap.restaurant.vo.RestaurantDecisionVo;
import com.ginkgo.foodmap.restaurant.vo.RestaurantVo;
import com.ginkgo.foodmap.security.service.CurrentActorService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 商户资料、正式相册和审核状态在一个MySQL事务提交；保留原申请图，不在事务中访问OSS。 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Transactional(isolation = Isolation.READ_COMMITTED)
public class RestaurantRequestReviewService {
    private final RestaurantRequestReviewMapper requests;
    private final RestaurantWriteMapper restaurants;
    private final RestaurantWriteService writes;
    private final CurrentActorService actors;
    private final ObjectMapper json;
    private final RestaurantPhotoMapper photos;
    private final MediaFileService media;

    /** 相同规范化内容重试返回首次回执，其他终态或不同通过内容返回409。 */
    public RestaurantDecisionVo approve(String id, RestaurantApproveRequest input) {
        long actor = administrator();
        var row = lockRequest(RestaurantIdentifiers.id(id));
        normalizeReview(input);
        if ("APPROVED".equals(row.getStatus())) {
            var saved = json.readValue(row.getApprovedData(), RestaurantApproveRequest.class);
            normalizeReview(saved);
            if (!saved.equals(input)) conflict();
            return receipt(row, true);
        }
        pending(row);
        RestaurantVo published;
        if ("CREATE".equals(row.getRequestType())) {
            published = writes.create(input).getRestaurant();
        } else {
            // 先前已锁住主行；READ_COMMITTED下此处读取的是锁定后的最新业务值，不做页面过期检测。
            var current = restaurants.selectManagementById(row.getRestaurantId());
            if (current == null || current.getDeletedAt() != null) conflict();
            // 窗口通过外键绑定食堂类型，包括已删除窗口；不能把有窗口的父食堂改为普通餐厅。
            if ("CANTEEN".equals(current.getRestaurantType()) && "REGULAR".equals(input.getRestaurantType())
                    && restaurants.hasWindows(current.getId())) conflict();
            row.setBeforeData(json.writeValueAsString(snapshot(current)));
            published = writes.update(row.getRestaurantId().toString(), input).getRestaurant();
        }
        row.setRestaurantId(Long.parseLong(published.getId()));
        publishPhotos(row);
        row.setReviewedBy(actor);
        row.setApprovedData(json.writeValueAsString(input));
        if (requests.approve(row) != 1) conflict();
        return receipt(requests.lock(row.getId()), false);
    }

    /** 商户主行已由更新或新建事务持有，位置分配按同店串行；任何文件/关联失败均回滚整次审核。 */
    private void publishPhotos(RestaurantChangeEntity request) {
        var ids = photos.requestFiles(request.getId());
        if (ids.isEmpty()) return;
        media.validateRestaurantPublication(ids, request.getSubmittedBy());
        long last = photos.lastPosition(request.getRestaurantId());
        if (last + ids.size() > Integer.MAX_VALUE) conflict();
        for (int index = 0; index < ids.size(); index++) {
            var row = new RestaurantPhotoEntity();
            row.setRestaurantId(request.getRestaurantId()); row.setSourceRequestId(request.getId());
            row.setMediaFileId(ids.get(index)); row.setPosition((int) (last + index + 1));
            if (photos.insert(row) != 1) conflict();
        }
    }

    /** 驳回不修改正式商户和图片；保留原申请及图片用于事后核对，原因相同才视为重试。 */
    public RestaurantDecisionVo reject(String id, String reason) {
        long actor = administrator();
        if (reason == null) throw new BusinessException(RestaurantErrorCode.INVALID_INPUT);
        String normalized = reason.replaceAll("(?U)^\\s+|\\s+$", "");
        if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > 200)
            throw new BusinessException(RestaurantErrorCode.INVALID_INPUT);
        var row = lockRequest(RestaurantIdentifiers.id(id));
        if ("REJECTED".equals(row.getStatus())) {
            if (!normalized.equals(row.getRejectReason())) conflict();
            return receipt(row, true);
        }
        pending(row);
        row.setReviewedBy(actor); row.setRejectReason(normalized);
        if (requests.reject(row) != 1) conflict();
        return receipt(requests.lock(row.getId()), false);
    }

    /** UPDATE统一先商户后申请，与用户纠错提交保持相同顺序；CREATE只锁申请，绝不锁已发布的新商户。 */
    private RestaurantChangeEntity lockRequest(long id) {
        var hint = requests.find(id);
        if (hint == null) throw new BusinessException(RestaurantErrorCode.REQUEST_NOT_FOUND);
        if ("UPDATE".equals(hint.getRequestType())) restaurants.lockById(hint.getRestaurantId());
        var row = requests.lock(id);
        if (row == null) throw new BusinessException(RestaurantErrorCode.REQUEST_NOT_FOUND);
        return row;
    }

    /** 快照仅保存可维护字段；归属、评分及原始投稿身份不会随审核被覆盖。 */
    private RestaurantApproveRequest snapshot(com.ginkgo.foodmap.restaurant.entity.RestaurantManagementEntity row) {
        var result = new RestaurantApproveRequest();
        result.setRestaurantType(row.getRestaurantType());
        result.setSupportsDineIn(row.getSupportsDineIn()); result.setSupportsDelivery(row.getSupportsDelivery());
        result.setName(row.getName()); result.setCategoryCode(row.getCategoryCode());
        result.setPriceKind(row.getPriceKind());
        result.setPricePerPerson(row.getPricePerPerson() == null ? null : java.math.BigDecimal.valueOf(row.getPricePerPerson()));
        result.setAddress(row.getAddress()); result.setLatitude(row.getLatitude()); result.setLongitude(row.getLongitude());
        result.setLocationConfirmed(!row.isNeedsLocation()); result.setBusinessStatus(row.getBusinessStatus());
        result.setTags(restaurants.selectTags(row.getId()));
        return result;
    }

    private long administrator() {
        var actor = actors.requireActor();
        if (!actor.isAdministrator()) throw new BusinessException(RestaurantErrorCode.FORBIDDEN);
        return actor.getUserId();
    }

    /** 仅审核内容比较忽略坐标小数尾零；不能改变用户投稿既有摘要的生成规则。 */
    private void normalizeReview(RestaurantSaveRequest input) {
        writes.normalizeChange(input);
        input.setLatitude(input.getLatitude().stripTrailingZeros());
        input.setLongitude(input.getLongitude().stripTrailingZeros());
    }

    private void pending(RestaurantChangeEntity row) { if (!"PENDING".equals(row.getStatus())) conflict(); }
    private void conflict() { throw new BusinessException(RestaurantErrorCode.STATE_CONFLICT); }

    private RestaurantDecisionVo receipt(RestaurantChangeEntity row, boolean replayed) {
        var result = new RestaurantDecisionVo(); result.setId(row.getId().toString()); result.setStatus(row.getStatus());
        result.setRestaurantId(row.getRestaurantId() == null ? null : row.getRestaurantId().toString());
        result.setReviewedBy(row.getReviewedBy().toString()); result.setReviewedAt(row.getReviewedAt());
        result.setRejectReason(row.getRejectReason()); result.setReplayed(replayed);
        result.setPublishedPhotos(photos.byRequest(row.getId()).stream().map(RestaurantPhotoService::view).toList());
        return result;
    }
}
