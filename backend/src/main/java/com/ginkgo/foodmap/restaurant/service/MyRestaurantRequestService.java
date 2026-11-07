package com.ginkgo.foodmap.restaurant.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.file.exception.FileErrorCode;
import com.ginkgo.foodmap.file.service.MediaFileService;
import com.ginkgo.foodmap.restaurant.dto.MyRestaurantRequestQuery;
import com.ginkgo.foodmap.restaurant.entity.MyRestaurantRequestEntity;
import com.ginkgo.foodmap.restaurant.exception.RestaurantErrorCode;
import com.ginkgo.foodmap.restaurant.mapper.MyRestaurantRequestMapper;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantCatalogMapper;
import com.ginkgo.foodmap.restaurant.support.RestaurantIdentifiers;
import com.ginkgo.foodmap.restaurant.vo.MyRestaurantRequestViews;
import com.ginkgo.foodmap.security.service.CurrentActorService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** 本人申请只读流程；服务自己取得当前身份，内部调用也不能自选其他申请人。 */
@Service
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MyRestaurantRequestService {
    private final CurrentActorService actors;
    private final MyRestaurantRequestMapper requests;
    private final RestaurantCatalogMapper catalog;
    private final MediaFileService files;
    private final ObjectMapper json;
    private static final List<String> FIELDS = List.of("name", "categoryCode", "restaurantType", "priceKind",
            "pricePerPerson", "address", "latitude", "longitude", "locationConfirmed", "businessStatus",
            "supportsDineIn", "supportsDelivery", "tags");

    /** 计数和当前页使用同一RR快照，审核并发完成时不会出现本次总数与列表状态错配。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MyRestaurantRequestViews.Page page(MyRestaurantRequestQuery query) {
        long owner = actors.requireActor().getUserId();
        var counts = new LinkedHashMap<String, Long>();
        for (String status : List.of("PENDING", "APPROVED", "REJECTED")) counts.put(status, 0L);
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
        result.setRequestType(row.getRequestType()); result.setStatus(row.getStatus());
        result.setName(name == null ? "未命名店铺" : name); result.setPhotoCount(count);
        result.setCreatedAt(row.getCreatedAt()); result.setReviewedAt(row.getReviewedAt());
        return result;
    }
}
