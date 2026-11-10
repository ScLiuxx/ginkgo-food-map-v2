package com.ginkgo.foodmap.restaurant.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.file.exception.FileErrorCode;
import com.ginkgo.foodmap.file.service.MediaFileService;
import com.ginkgo.foodmap.restaurant.dto.MyRestaurantRequestQuery;
import com.ginkgo.foodmap.restaurant.dto.RestaurantChangeEditRequest;
import com.ginkgo.foodmap.restaurant.entity.MyRestaurantRequestEntity;
import com.ginkgo.foodmap.restaurant.exception.RestaurantErrorCode;
import com.ginkgo.foodmap.restaurant.mapper.MyRestaurantRequestMapper;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantCatalogMapper;
import com.ginkgo.foodmap.restaurant.support.RestaurantIdentifiers;
import com.ginkgo.foodmap.restaurant.vo.MyRestaurantRequestViews;
import com.ginkgo.foodmap.security.service.CurrentActorService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.Validator;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 本人申请读写入口；服务自己取得当前身份，内部调用也不能自选其他申请人。 */
@Service
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MyRestaurantRequestService {
    private final CurrentActorService actors;
    private final MyRestaurantRequestMapper requests;
    private final RestaurantCatalogMapper catalog;
    private final MediaFileService files;
    private final RestaurantWriteService validation;
    private final Validator validator;
    private final ObjectMapper json;
    private static final List<String> FIELDS = List.of("name", "categoryCode", "restaurantType", "priceKind",
            "pricePerPerson", "address", "latitude", "longitude", "locationConfirmed", "businessStatus",
            "supportsDineIn", "supportsDelivery", "tags");

    /** 计数和当前页使用同一RR快照，审核并发完成时不会出现本次总数与列表状态错配。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MyRestaurantRequestViews.Page page(MyRestaurantRequestQuery query) {
        long owner = actors.requireActor().getUserId();
        var counts = new LinkedHashMap<String, Long>();
        for (String status : List.of("PENDING", "APPROVED", "REJECTED", "WITHDRAWN")) counts.put(status, 0L);
        requests.counts(owner).forEach(row -> counts.put(row.getStatus(), row.getTotal()));
        counts.put("ALL", counts.values().stream().mapToLong(Long::longValue).sum());
        var rows = requests.page(owner, query, ((long) query.getPage() - 1) * query.getPageSize());
        var result = new MyRestaurantRequestViews.Page();
        result.setUserId(Long.toString(owner));
        result.setRecords(rows.stream().map(row -> item(row, row.getRequestName(), row.getPhotoCount())).toList());
        result.setCounts(counts); result.setTotal(counts.get(query.getStatus()));
        result.setPage(query.getPage()); result.setPageSize(query.getPageSize());
        result.setTotalPages((result.getTotal() + query.getPageSize() - 1) / query.getPageSize());
        return result;
    }

    /** 申请不因目标店铺删除而消失；仅返回自己的原始内容/最终通过内容，不返回管理端当前或before资料。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MyRestaurantRequestViews.Detail detail(String id) {
        long owner = actors.requireActor().getUserId();
        var row = require(owner, RestaurantIdentifiers.id(id));
        var names = new HashMap<String, String>();
        catalog.selectCategories().forEach(category -> names.put(category.getCode(), category.getName()));
        var positions = requests.positions(owner, row.getId());
        var proposed = snapshot(row.getProposedData(), names);
        var result = new MyRestaurantRequestViews.Detail();
        result.setUserId(Long.toString(owner));
        result.setApplication(item(row, Objects.toString(proposed.get("name"), "未命名店铺"), positions.size()));
        result.setProposed(proposed);
        result.setApproved(snapshot(row.getApprovedData(), names));
        result.setRejectReason(row.getRejectReason());
        result.setPhotoPositions(positions);
        result.setTargetAvailable(row.getRestaurantId() != null && requests.targetAvailable(row.getRestaurantId()));
        return result;
    }

    /** 先核对申请归属，再解析绑定位置；OSS网络读取不包在数据库事务中，也不开放任意文件ID读取。 */
    public byte[] photo(String id, String position) {
        long owner = actors.requireActor().getUserId();
        long requestId = RestaurantIdentifiers.id(id);
        require(owner, requestId);
        if (position == null || !position.matches("[0-4]")) throw new BusinessException(RestaurantErrorCode.INVALID_INPUT);
        Long fileId = requests.photo(owner, requestId, Integer.parseInt(position));
        if (fileId == null) throw new BusinessException(FileErrorCode.NOT_FOUND);
        return files.readOwnRestaurantBound(fileId);
    }

    /**
     * 本人整体修改待审申请。
     *
     * <p>先按本人范围读取类型与目标以便校验分类，再锁定申请行复核状态，最后原子替换业务快照、
     * 摘要和附图。已通过、已驳回或已撤回的申请不可修改；并发审核与修改只有一个能提交成功。
     */
    @Transactional
    public void edit(String id, RestaurantChangeEditRequest request) {
        long owner = actors.requireActor().getUserId();
        long requestId = RestaurantIdentifiers.id(id);
        var hint = requests.hint(owner, requestId);
        if (hint == null) {
            throw new BusinessException(RestaurantErrorCode.REQUEST_NOT_FOUND);
        }
        if (!"PENDING".equals(hint.getStatus())) {
            throw new BusinessException(RestaurantErrorCode.STATE_CONFLICT, "仅待审核申请可以修改");
        }
        String currentCategory = null;
        if ("UPDATE".equals(hint.getRequestType())) {
            var target = requests.target(hint.getRestaurantId());
            // 纠错目标已被删除时保留历史申请可读，但不再允许继续修改后等待审核。
            if (target == null) {
                throw new BusinessException(RestaurantErrorCode.NOT_FOUND);
            }
            // 食堂目标没有分类，和店铺目标一样交给同一套分类校验处理。
            currentCategory = target.getCategoryCode();
        }
        if (!validator.validate(request).isEmpty()) {
            throw new BusinessException(RestaurantErrorCode.INVALID_INPUT);
        }
        validation.validateChange(request, currentCategory);
        var locked = requests.lock(owner, requestId);
        if (locked == null) {
            throw new BusinessException(RestaurantErrorCode.REQUEST_NOT_FOUND);
        }
        if (!"PENDING".equals(locked.getStatus())) {
            throw new BusinessException(RestaurantErrorCode.STATE_CONFLICT, "仅待审核申请可以修改");
        }
        List<Long> photoIds = parsePhotoIds(request);
        replacePhotos(owner, requestId, photoIds);
        String payload = json.writeValueAsString(request);
        if (requests.updateContent(owner, requestId, payload, hash(payload)) != 1) {
            throw new BusinessException(RestaurantErrorCode.STATE_CONFLICT);
        }
    }

    /**
     * 本人撤回待审申请。
     *
     * <p>撤回是独立终态，不写审核人和通过/驳回资料；重复撤回返回同一结果，已通过或已驳回返回409。
     */
    @Transactional
    public void withdraw(String id) {
        long owner = actors.requireActor().getUserId();
        long requestId = RestaurantIdentifiers.id(id);
        var locked = requests.lock(owner, requestId);
        if (locked == null) {
            throw new BusinessException(RestaurantErrorCode.REQUEST_NOT_FOUND);
        }
        // 重复撤回和相同回执重试都返回成功，不再次修改撤回时间。
        if ("WITHDRAWN".equals(locked.getStatus())) {
            return;
        }
        if (!"PENDING".equals(locked.getStatus())) {
            throw new BusinessException(RestaurantErrorCode.STATE_CONFLICT, "仅待审核申请可以撤回");
        }
        if (requests.withdraw(owner, requestId) != 1) {
            throw new BusinessException(RestaurantErrorCode.STATE_CONFLICT);
        }
    }

    /**
     * 以当前附图整体替换待审申请附图。
     *
     * <p>只释放新列表不再保留的旧图，交集图片继续复用原绑定，避免重复绑定失败。
     * 旧图登记删除、关联重建和新图绑定与申请内容更新处于同一事务。
     */
    private void replacePhotos(long owner, long requestId, List<Long> photoIds) {
        List<Long> existing = requests.photoIds(owner, requestId);
        if (existing.equals(photoIds)) {
            return;
        }
        List<Long> removed = existing.stream().filter(id -> !photoIds.contains(id)).toList();
        List<Long> added = photoIds.stream().filter(id -> !existing.contains(id)).toList();
        if (!removed.isEmpty()) {
            files.releaseRestaurantPhotos(removed, owner);
        }
        requests.deletePhotos(owner, requestId);
        if (!added.isEmpty()) {
            files.bindRestaurantPhotos(added, owner);
        }
        for (int position = 0; position < photoIds.size(); position++) {
            requests.insertPhoto(requestId, position, photoIds.get(position));
        }
    }

    /** 图片ID按long范围解析并保留顺序；重复图片会破坏独占绑定，必须在写入前拒绝。 */
    private List<Long> parsePhotoIds(RestaurantChangeEditRequest request) {
        List<Long> photoIds;
        try {
            photoIds = request.getPhotoIds().stream().map(Long::parseLong).toList();
        } catch (NumberFormatException invalid) {
            throw new BusinessException(RestaurantErrorCode.INVALID_INPUT);
        }
        if (photoIds.stream().distinct().count() != photoIds.size()) {
            throw new BusinessException(RestaurantErrorCode.INVALID_INPUT);
        }
        return photoIds;
    }

    /** 与首次提交使用同一摘要算法，便于统一比较规范化内容。 */
    private byte[] hash(String payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private MyRestaurantRequestEntity require(long owner, long id) {
        var row = requests.detail(owner, id);
        // 不区分别人的申请和不存在的申请，避免用ID探测他人记录。
        if (row == null) throw new BusinessException(RestaurantErrorCode.REQUEST_NOT_FOUND);
        return row;
    }

    /** 投影历史JSON白名单，忽略旧字段和内部photoIds/UUID，不用当前提交DTO校验历史快照。 */
    private Map<String, Object> snapshot(String source, Map<String, String> categories) {
        if (source == null) return null;
        Map<String, Object> raw = json.readValue(source, new TypeReference<Map<String, Object>>() { });
        var result = new LinkedHashMap<String, Object>();
        FIELDS.forEach(field -> result.put(field, raw.get(field)));
        String code = (String) raw.get("categoryCode");
        result.put("categoryName", code == null ? null : categories.getOrDefault(code, code));
        return result;
    }

    private MyRestaurantRequestViews.Item item(MyRestaurantRequestEntity row, String name, int count) {
        var result = new MyRestaurantRequestViews.Item();
        result.setId(row.getId().toString());
        result.setRestaurantId(row.getRestaurantId() == null ? null : row.getRestaurantId().toString());
        result.setPreviousRequestId(
                row.getPreviousRequestId() == null ? null : row.getPreviousRequestId().toString());
        result.setRequestType(row.getRequestType()); result.setStatus(row.getStatus());
        result.setName(name == null ? "未命名店铺" : name); result.setPhotoCount(count);
        result.setCreatedAt(row.getCreatedAt()); result.setReviewedAt(row.getReviewedAt());
        result.setWithdrawnAt(row.getWithdrawnAt());
        return result;
    }
}
