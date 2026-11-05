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
 * 餐厅写入应用服务。正式资料仅管理员可维护；普通投稿仅复用输入校验。
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
    private final RestaurantPhotoService albums;
    private final RestaurantSearchOutboxService searchOutbox;

    /** 仅管理员可获取管理资料，含删除记录；公开详情仍隐藏已删除商户。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @PreAuthorize("hasRole('ADMIN')")
    public RestaurantManagementVo management(String id) {
        var actor = requireAdministrator();
        var row = mapper.selectManagementById(RestaurantIdentifiers.id(id));
        requireAccessible(row, actor);
        return managementVo(row);
    }

    /** 管理员创建正式记录；不开放历史导入、无位置发布或客户端指定创建者。 */
    @PreAuthorize("hasRole('ADMIN')")
    public RestaurantManagementVo create(RestaurantSaveRequest request) {
        var actor = requireAdministrator();
        var row = normalize(request);
        requireCategory(row.getCategoryCode(), null, row.getRestaurantType());
        var tags = normalizeTags(request.getTags());
        row.setCreatedBy(actor.getUserId());
        row.setUpdatedBy(actor.getUserId());
        mapper.insert(row);
        if (!tags.isEmpty()) mapper.insertTags(row.getId(), tags);
        // 插入商户取得版本1后，在当前事务记录任务；后续审核/相册失败也会撤销任务。
        searchOutbox.record(row.getId());
        return managementVo(mapper.selectManagementById(row.getId()));
    }

    /** PUT全量替换业务字段及标签，来源/创建者保持原值；任何失败都回滚主表与标签。 */
    @PreAuthorize("hasRole('ADMIN')")
    public RestaurantManagementVo update(String id, RestaurantSaveRequest request) {
        var actor = requireAdministrator();
        var replacement = normalize(request);
        var current = lockAccessible(RestaurantIdentifiers.id(id), actor);
        requireActive(current);
        // 旧客户端省略类型则保持原值；显式类型切换须在父行锁内检查全部窗口关联。
        if ("CANTEEN".equals(current.getRestaurantType()) && "REGULAR".equals(replacement.getRestaurantType())
                && mapper.hasWindows(current.getId())) {
            throw new BusinessException(RestaurantErrorCode.STATE_CONFLICT, "存在窗口关联的食堂不能改为普通餐厅");
        }
        requireCategory(replacement.getCategoryCode(), current.getCategoryCode(),
                replacement.getRestaurantType() == null ? current.getRestaurantType() : replacement.getRestaurantType());
        var tags = normalizeTags(request.getTags());
        replacement.setId(current.getId());
        if (mapper.updateCurrent(replacement, actor.getUserId(), actor.isAdministrator()) != 1) conflict();
        mapper.deleteTags(current.getId());
        if (!tags.isEmpty()) mapper.insertTags(current.getId(), tags);
        searchOutbox.record(current.getId());
        return managementVo(mapper.selectManagementById(current.getId()));
    }

    /** 删除只更改父状态，保留标签；重复删除返回404/409，不重复修改删除元数据。 */
    @PreAuthorize("hasRole('ADMIN')")
    public RestaurantManagementVo delete(String id) {
        var actor = requireAdministrator();
        var current = lockAccessible(RestaurantIdentifiers.id(id), actor);
        requireActive(current);
        if (mapper.softDeleteCurrent(current.getId(), actor.getUserId(), actor.isAdministrator()) != 1) conflict();
        searchOutbox.record(current.getId());
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
        searchOutbox.record(current.getId());
        return managementVo(mapper.selectManagementById(current.getId()));
    }

    /** 纠错共用现有字段、分类及标签校验，只规范化申请数据，不修改正式商户。 */
    public void validateChange(RestaurantSaveRequest request, String currentCategory) {
        normalizeChange(request);
        requireCategory(request.getCategoryCode(), currentCategory, inputType(request));
    }

    /** 审核重试仅规范化业务输入，不重新判断后来可能被停用的分类；不赋予任何正式写权限。 */
    public void normalizeChange(RestaurantSaveRequest request) {
        var row = normalize(request);
        request.setName(row.getName());
        request.setAddress(row.getAddress());
        request.setPricePerPerson(row.getPricePerPerson() == null ? null : BigDecimal.valueOf(row.getPricePerPerson()));
        request.setTags(normalizeTags(request.getTags()));
    }

    /** 普通投稿人只能提交申请；即使内部直接调用服务，也不能按created_by绕过审核修改公开资料。 */
    private AuthenticatedActor requireAdministrator() {
        var actor = actors.requireActor();
        if (!actor.isAdministrator()) throw new BusinessException(RestaurantErrorCode.FORBIDDEN);
        return actor;
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

    /** 食堂不设分类；普通餐厅可保留已停用旧类，新建/切换必须选启用分类。 */
    private void requireCategory(String code, String currentCode, String type) {
        if ("CANTEEN".equals(type)) {
            if (code != null) invalid("食堂不设置美食分类，请使用null");
            return;
        }
        if (code == null) invalid("普通餐厅必须选择美食分类");
        var category = mapper.lockCategory(code);
        if (category == null || (!category.isEnabled() && !code.equals(currentCode))) {
            throw new BusinessException(RestaurantErrorCode.CATEGORY_UNAVAILABLE);
        }
    }

    /** 不同请求各自保留序列化契约；旧直接输入省略类型时按普通餐厅校验新建。 */
    private String inputType(RestaurantSaveRequest request) {
        if (request instanceof com.ginkgo.foodmap.restaurant.dto.RestaurantDirectSaveRequest direct)
            return direct.getRestaurantType() == null ? "REGULAR" : direct.getRestaurantType();
        if (request instanceof com.ginkgo.foodmap.restaurant.dto.RestaurantApproveRequest approved)
            return approved.getRestaurantType();
        if (request instanceof com.ginkgo.foodmap.restaurant.dto.RestaurantChangeRequest changed)
            return changed.getRestaurantType();
        return "REGULAR";
    }

    private RestaurantManagementVo managementVo(RestaurantManagementEntity row) {
        var result = new RestaurantManagementVo();
        result.setRestaurant(RestaurantReadService.toVo(row, mapper.selectTags(row.getId())));
        result.getRestaurant().setCoverPhoto(albums.covers(List.of(row.getId())).get(row.getId()));
        result.setDeleted(row.getDeletedAt() != null);
        return result;
    }

    /** Unicode按码点计数，首尾包含全角/不换行空格也规范化；数据库CHECK仅作最后防线。 */
    private RestaurantManagementEntity normalize(RestaurantSaveRequest request) {
        if (request == null || !validator.validate(request).isEmpty()) invalid("请检查必填项、编码、位置确认和标签数量");
        var row = new RestaurantManagementEntity();
        // 显式类型的直接维护与审核都完整写入三态；旧请求省略类型时由更新SQL保留已有值。
        if (request instanceof com.ginkgo.foodmap.restaurant.dto.RestaurantDirectSaveRequest direct) {
            row.setRestaurantType(direct.getRestaurantType());
            row.setSupportsDineIn(direct.getSupportsDineIn());
            row.setSupportsDelivery(direct.getSupportsDelivery());
        }
        if (request instanceof com.ginkgo.foodmap.restaurant.dto.RestaurantApproveRequest approved) {
            row.setRestaurantType(approved.getRestaurantType());
            row.setSupportsDineIn(approved.getSupportsDineIn());
            row.setSupportsDelivery(approved.getSupportsDelivery());
        }
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
