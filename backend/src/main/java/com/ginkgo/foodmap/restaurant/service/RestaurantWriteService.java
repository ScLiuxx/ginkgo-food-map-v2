package com.ginkgo.foodmap.restaurant.service;

import com.ginkgo.foodmap.restaurant.support.RestaurantIdentifiers;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.restaurant.dto.RestaurantSaveRequest;
import com.ginkgo.foodmap.restaurant.entity.RestaurantManagementEntity;
import com.ginkgo.foodmap.restaurant.exception.RestaurantErrorCode;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantWriteMapper;
import com.ginkgo.foodmap.restaurant.vo.RestaurantManagementVo;
import com.ginkgo.foodmap.security.dto.AuthenticatedActor;
import com.ginkgo.foodmap.security.service.CurrentActorService;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 餐厅写入应用服务。归属来自认证模块，角色由安全链实时加载；所有公开方法均要求认证。
 * 主表与标签在同一事务更新；主行锁串行化同店写入，最后成功保存的完整内容生效。
 */
@Service
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class RestaurantWriteService {
    private final RestaurantWriteMapper mapper;
    private final CurrentActorService actors;
    private final Validator validator;

    /** 只有创建者/管理员可获取管理资料；删除记录只供管理员管理，公开详情仍隐藏。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RestaurantManagementVo management(String id) {
        var actor = actors.requireActor();
        var row = mapper.selectManagementById(RestaurantIdentifiers.id(id));
        requireAccessible(row, actor);
        return managementVo(row);
    }

    /** 登录账号创建真实归属记录；不开放历史导入、无位置发布或客户端指定创建者。 */
    public RestaurantManagementVo create(RestaurantSaveRequest request) {
        var actor = actors.requireActor();
        var row = normalize(request);
        requireCategory(row.getCategoryCode(), null);
        var tags = normalizeTags(request.getTags());
        row.setCreatedBy(actor.getUserId());
        row.setUpdatedBy(actor.getUserId());
        mapper.insert(row);
        if (!tags.isEmpty()) mapper.insertTags(row.getId(), tags);
        return managementVo(mapper.selectManagementById(row.getId()));
    }

    /** PUT全量替换业务字段及标签，来源/创建者保持原值；任何失败都回滚主表与标签。 */
    public RestaurantManagementVo update(String id, RestaurantSaveRequest request) {
        var actor = actors.requireActor();
        var replacement = normalize(request);
        var current = lockAccessible(RestaurantIdentifiers.id(id), actor);
        requireActive(current);
        requireCategory(replacement.getCategoryCode(), current.getCategoryCode());
        var tags = normalizeTags(request.getTags());
        replacement.setId(current.getId());
        if (mapper.updateCurrent(replacement, actor.getUserId(), actor.isAdministrator()) != 1) conflict();
        mapper.deleteTags(current.getId());
        if (!tags.isEmpty()) mapper.insertTags(current.getId(), tags);
        return managementVo(mapper.selectManagementById(current.getId()));
    }

    /** 删除只更改父状态，保留标签；重复删除返回404/409，不重复修改删除元数据。 */
    public RestaurantManagementVo delete(String id) {
        var actor = actors.requireActor();
        var current = lockAccessible(RestaurantIdentifiers.id(id), actor);
        requireActive(current);
        if (mapper.softDeleteCurrent(current.getId(), actor.getUserId(), actor.isAdministrator()) != 1) conflict();
        return managementVo(mapper.selectManagementById(current.getId()));
    }

    /** 管理员角色单独校验；清空当前删除标记而不改归属、不重建或清空历史标签。 */
    @PreAuthorize("hasRole('ADMIN')")
    public RestaurantManagementVo restore(String id) {
        var actor = actors.requireActor();
        // 方法级角色检查之外保留显式防线，模块内部调用也不能把普通操作者当作管理员。
        if (!actor.isAdministrator()) throw new BusinessException(RestaurantErrorCode.FORBIDDEN);
        var current = lockAccessible(RestaurantIdentifiers.id(id), actor);
        if (current.getDeletedAt() == null) throw new BusinessException(RestaurantErrorCode.STATE_CONFLICT);
        if (mapper.restoreCurrent(current.getId(), actor.getUserId()) != 1) conflict();
        return managementVo(mapper.selectManagementById(current.getId()));
    }

    /** 纠错共用现有字段、分类及标签校验，只规范化申请数据，不修改正式商户。 */
    public void validateChange(RestaurantSaveRequest request, String currentCategory) {
        var row = normalize(request);
        requireCategory(row.getCategoryCode(), currentCategory);
        request.setName(row.getName());
        request.setAddress(row.getAddress());
        request.setPricePerPerson(row.getPricePerPerson() == null ? null : BigDecimal.valueOf(row.getPricePerPerson()));
        request.setTags(normalizeTags(request.getTags()));
    }

    private RestaurantManagementEntity lockAccessible(long id, AuthenticatedActor actor) {
        var row = mapper.lockById(id);
        requireAccessible(row, actor);
        return row;
    }

    private void requireAccessible(RestaurantManagementEntity row, AuthenticatedActor actor) {
        if (row == null || (row.getDeletedAt() != null && !actor.isAdministrator())) {
            throw new BusinessException(RestaurantErrorCode.NOT_FOUND);
        }
        if (!actor.isAdministrator() && !Objects.equals(row.getCreatedBy(), actor.getUserId())) {
            throw new BusinessException(RestaurantErrorCode.FORBIDDEN);
        }
    }

    private void requireActive(RestaurantManagementEntity row) {
        if (row.getDeletedAt() != null) throw new BusinessException(RestaurantErrorCode.STATE_CONFLICT);
    }

    /** 同一已停用分类可以保持不变；新建/切换必须选启用分类，绝不静默改成“其他”。 */
    private void requireCategory(String code, String currentCode) {
        var category = mapper.lockCategory(code);
        if (category == null || (!category.isEnabled() && !code.equals(currentCode))) {
            throw new BusinessException(RestaurantErrorCode.CATEGORY_UNAVAILABLE);
        }
    }

    private RestaurantManagementVo managementVo(RestaurantManagementEntity row) {
        var result = new RestaurantManagementVo();
        result.setRestaurant(RestaurantReadService.toVo(row, mapper.selectTags(row.getId())));
        result.setDeleted(row.getDeletedAt() != null);
        return result;
    }

    /** Unicode按码点计数，首尾包含全角/不换行空格也规范化；数据库CHECK仅作最后防线。 */
    private RestaurantManagementEntity normalize(RestaurantSaveRequest request) {
        if (request == null || !validator.validate(request).isEmpty()) invalid("请检查必填项、编码、位置确认和标签数量");
        var row = new RestaurantManagementEntity();
        row.setName(text(request.getName(), 40, false));
        row.setAddress(text(request.getAddress(), 80, true));
        row.setCategoryCode(request.getCategoryCode());
        row.setPriceKind(request.getPriceKind());
        BigDecimal price = request.getPricePerPerson();
        if ("UNKNOWN".equals(request.getPriceKind())) {
            if (price != null) invalid("未知人均必须使用null金额");
        } else {
            if (price == null || price.stripTrailingZeros().scale() > 0
                    || price.compareTo(BigDecimal.ONE) < 0 || price.compareTo(BigDecimal.valueOf(5000)) > 0) {
                invalid("人均必须是1至5000的整数元");
            }
            row.setPricePerPerson(price.intValueExact());
        }
        coordinate(request.getLatitude(), 3, 54);
        coordinate(request.getLongitude(), 73, 136);
        row.setLatitude(request.getLatitude());
        row.setLongitude(request.getLongitude());
        row.setNeedsLocation(false);
        row.setBusinessStatus(request.getBusinessStatus());
        return row;
    }

    private void coordinate(BigDecimal coordinate, int min, int max) {
        if (coordinate == null || coordinate.compareTo(BigDecimal.valueOf(min)) < 0
                || coordinate.compareTo(BigDecimal.valueOf(max)) > 0 || coordinate.stripTrailingZeros().scale() > 6) {
            invalid("请确认范围内且最多6位小数的GCJ-02坐标");
        }
    }

    /** 去空白后按MySQL实际排序规则去重，保留首项，最多8个且总Unicode码点数80。 */
    private List<String> normalizeTags(List<String> submitted) {
        var normalized = submitted.stream().map(tag -> text(tag, 80, false)).toList();
        if (normalized.isEmpty()) return List.of();
        var tags = mapper.distinctTags(normalized);
        int total = tags.stream().mapToInt(tag -> tag.codePointCount(0, tag.length())).sum();
        if (tags.size() > 8 || total > 80) invalid("标签最多8个，去重后总字符数最多80");
        return tags;
    }

    private String text(String value, int max, boolean nullable) {
        if (value == null) {
            if (nullable) return null;
            invalid("必填文字不能为空");
        }
        String normalized = value.replaceAll("(?U)^\\s+|\\s+$", "");
        if (normalized.isEmpty()) {
            if (nullable) return null;
            invalid("必填文字不能为空");
        }
        if (normalized.codePointCount(0, normalized.length()) > max) invalid("文字超过允许字符数");
        return normalized;
    }

    private void conflict() {
        throw new BusinessException(RestaurantErrorCode.STATE_CONFLICT);
    }

    private void invalid(String message) {
        throw new BusinessException(RestaurantErrorCode.INVALID_INPUT, message);
    }
}
