package com.ginkgo.foodmap.restaurant.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.file.service.MediaFileService;
import com.ginkgo.foodmap.restaurant.dto.RestaurantRequestQuery;
import com.ginkgo.foodmap.restaurant.entity.RestaurantChangeEntity;
import com.ginkgo.foodmap.restaurant.exception.RestaurantErrorCode;
import com.ginkgo.foodmap.restaurant.mapper.*;
import com.ginkgo.foodmap.restaurant.support.RestaurantIdentifiers;
import com.ginkgo.foodmap.restaurant.vo.RestaurantRequestViews;
import com.ginkgo.foodmap.user.dto.PublicAuthor;
import com.ginkgo.foodmap.user.service.PublicAuthorService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** 管理申请的公开应用服务；仅查询，不改变审核状态，不把审核快照当作正式商户发布。 */
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class RestaurantRequestReadService {
    private final RestaurantRequestReadMapper requests;
    private final RestaurantWriteMapper restaurants;
    private final RestaurantCatalogMapper catalog;
    private final PublicAuthorService authors;
    private final MediaFileService files;
    private final ObjectMapper json;
    private final RestaurantPhotoMapper publishedPhotos;
    private static final List<String> FIELDS =
            List.of(
                    "name",
                    "categoryCode",
                    "restaurantType",
                    "priceKind",
                    "pricePerPerson",
                    "address",
                    "latitude",
                    "longitude",
                    "locationConfirmed",
                    "businessStatus",
                    "supportsDineIn",
                    "supportsDelivery",
                    "tags");

    /** 一个快照内读取分页、全部状态计数和批量署名，不逐条查账号，空页不扩大返回范围。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RestaurantRequestViews.Page page(RestaurantRequestQuery query) {
        query.setKeyword(query.getKeyword() == null ? "" : query.getKeyword().trim());
        query.setPattern(
                "%"
                        + query.getKeyword()
                                .replace("!", "!!")
                                .replace("%", "!%")
                                .replace("_", "!_")
                        + "%");
        var counts = new LinkedHashMap<String, Long>();
        for (String status : List.of("PENDING", "APPROVED", "REJECTED")) {
            counts.put(status, 0L);
        }
        requests.counts(query).forEach(row -> counts.put(row.getStatus(), row.getTotal()));
        var rows = requests.page(query, ((long) query.getPage() - 1) * query.getPageSize());
        var names =
                authors.find(
                        rows.stream()
                                .map(RestaurantChangeEntity::getSubmittedBy)
                                .distinct()
                                .toList());
        var result = new RestaurantRequestViews.Page();
        result.setRecords(
                rows.stream()
                        .map(row -> item(row, row.getRequestName(), row.getPhotoCount(), names))
                        .toList());
        long total = counts.get(query.getStatus());
        result.setTotal(total);
        result.setPage(query.getPage());
        result.setPageSize(query.getPageSize());
        result.setTotalPages((total + query.getPageSize() - 1) / query.getPageSize());
        result.setCounts(counts);
        return result;
    }

    /** 当前资料允许管理员核对已删除目标，但明确标记，不将其重新公开；新店当前资料为NULL。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RestaurantRequestViews.Detail detail(String id) {
        var row = requireRequest(RestaurantIdentifiers.id(id));
        var categories = new HashMap<String, String>();
        catalog.selectCategories()
                .forEach(category -> categories.put(category.getCode(), category.getName()));
        var proposed = snapshot(row.getProposedData(), categories);
        var positions = requests.positions(row.getId());
        var result = new RestaurantRequestViews.Detail();
        result.setApplication(
                item(
                        row,
                        Objects.toString(proposed.get("name"), "未命名商户"),
                        positions.size(),
                        authors.find(List.of(row.getSubmittedBy()))));
        result.setProposed(proposed);
        result.setPublishedPhotos(
                publishedPhotos.byRequest(row.getId()).stream()
                        .map(RestaurantPhotoService::view)
                        .toList());
        if (row.getRestaurantId() != null) {
            var current = restaurants.selectManagementById(row.getRestaurantId());
            if (current != null) {
                var values = new LinkedHashMap<String, Object>();
                values.put("name", current.getName());
                values.put("categoryCode", current.getCategoryCode());
                values.put(
                        "categoryName",
                        categories.getOrDefault(
                                current.getCategoryCode(), current.getCategoryCode()));
                values.put("restaurantType", current.getRestaurantType());
                values.put("priceKind", current.getPriceKind());
                values.put("pricePerPerson", current.getPricePerPerson());
                values.put("address", current.getAddress());
                values.put("latitude", current.getLatitude());
                values.put("longitude", current.getLongitude());
                values.put("locationConfirmed", !current.isNeedsLocation());
                values.put("businessStatus", current.getBusinessStatus());
                values.put("supportsDineIn", current.getSupportsDineIn());
                values.put("supportsDelivery", current.getSupportsDelivery());
                values.put("tags", restaurants.selectTags(current.getId()));
                result.setCurrent(values);
                result.setTargetDeleted(current.getDeletedAt() != null);
            }
        }
        result.setPhotos(
                positions.stream()
                        .map(
                                position -> {
                                    var photo = new RestaurantRequestViews.Photo();
                                    photo.setPosition(position);
                                    photo.setUrl(
                                            "/api/admin/restaurant-requests/"
                                                    + row.getId()
                                                    + "/photos/"
                                                    + position);
                                    return photo;
                                })
                        .toList());
        result.setReviewedBy(row.getReviewedBy() == null ? null : row.getReviewedBy().toString());
        result.setReviewedAt(row.getReviewedAt());
        result.setRejectReason(row.getRejectReason());
        result.setApproved(snapshot(row.getApprovedData(), categories));
        result.setBefore(snapshot(row.getBeforeData(), categories));
        return result;
    }

    /** 先验证管理身份及申请图片关联，再由file服务核验用途/归属/绑定状态；OSS读取不占数据库事务。 */
    public byte[] photo(String id, String position) {
        var row = requireRequest(RestaurantIdentifiers.id(id));
        if (position == null || !position.matches("[0-4]")) {
            throw new BusinessException(RestaurantErrorCode.INVALID_INPUT);
        }
        Long fileId = requests.photo(row.getId(), Integer.parseInt(position));
        if (fileId == null) {
            throw new BusinessException(com.ginkgo.foodmap.file.exception.FileErrorCode.NOT_FOUND);
        }
        return files.readRestaurantBound(fileId, row.getSubmittedBy());
    }

    private RestaurantChangeEntity requireRequest(long id) {
        var row = requests.detail(id);
        if (row == null) {
            throw new BusinessException(RestaurantErrorCode.REQUEST_NOT_FOUND);
        }
        return row;
    }

    /** 旧快照可能含已停用字段，读取时只投影业务白名单，不能用当前提交DTO反序列化而拒绝历史资料。 */
    private Map<String, Object> snapshot(String source, Map<String, String> categories) {
        if (source == null) {
            return null;
        }
        Map<String, Object> raw =
                json.readValue(source, new TypeReference<Map<String, Object>>() {});
        var result = new LinkedHashMap<String, Object>();
        FIELDS.forEach(field -> result.put(field, raw.get(field)));
        // 保留历史快照原值；新规则下食堂分类及展示名称均为NULL，不能转换成字符串"null"。
        String code = (String) raw.get("categoryCode");
        result.put("categoryName", code == null ? null : categories.getOrDefault(code, code));
        return result;
    }

    private RestaurantRequestViews.Item item(
            RestaurantChangeEntity row, String name, int count, Map<String, PublicAuthor> names) {
        var result = new RestaurantRequestViews.Item();
        result.setId(row.getId().toString());
        result.setRequestType(row.getRequestType());
        result.setStatus(row.getStatus());
        result.setName(name == null ? "未命名商户" : name);
        result.setRestaurantId(
                row.getRestaurantId() == null ? null : row.getRestaurantId().toString());
        result.setPreviousRequestId(
                row.getPreviousRequestId() == null ? null : row.getPreviousRequestId().toString());
        result.setSubmittedBy(row.getSubmittedBy().toString());
        var author = names.get(row.getSubmittedBy().toString());
        result.setSubmitterName(author == null ? "用户不可用" : author.getUsername());
        result.setCreatedAt(row.getCreatedAt());
        result.setPhotoCount(count);
        return result;
    }
}
