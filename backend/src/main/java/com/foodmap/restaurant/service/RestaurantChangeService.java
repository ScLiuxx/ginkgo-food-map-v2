package com.foodmap.restaurant.service;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.restaurant.dto.RestaurantChangeRequest;
import com.foodmap.restaurant.dto.RestaurantSubmissionRequest;
import com.foodmap.restaurant.entity.RestaurantChangeEntity;
import com.foodmap.restaurant.entity.RestaurantEntity;
import com.foodmap.restaurant.exception.RestaurantErrorCode;
import com.foodmap.restaurant.mapper.RestaurantChangeMapper;
import com.foodmap.restaurant.policy.RestaurantSubmissionHashPolicy;
import com.foodmap.restaurant.support.RestaurantIdentifiers;
import com.foodmap.restaurant.vo.RestaurantSubmissionVo;
import com.foodmap.review.service.ReviewSubmissionLifecycleService;
import com.foodmap.review.service.ReviewSubmissionService;
import com.foodmap.security.service.CurrentActorService;

import jakarta.validation.Validator;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.Objects;

/** 用户可为任意公开商户提出纠错；仅落待审快照，不拥有直接修改公开资料的权限。 */
@Service
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class RestaurantChangeService {
    private final RestaurantChangeMapper mapper;
    private final RestaurantWriteService validation;
    private final CurrentActorService actors;
    private final Validator validator;
    private final ObjectMapper json;
    private final com.foodmap.file.service.MediaFileService media;
    private final ReviewSubmissionService reviewSubmissions;
    private final ReviewSubmissionLifecycleService reviewLifecycle;

    /** 返回申请ID；相同账号/请求ID/规范化内容重试返回同一申请，不重复创建。 */
    @Transactional
    public String submit(String targetId, RestaurantChangeRequest request) {
        if (!validator.validate(request).isEmpty()) {
            throw new BusinessException(RestaurantErrorCode.INVALID_INPUT);
        }
        long id = RestaurantIdentifiers.id(targetId);
        var target = mapper.lockTarget(id);
        if (target == null) {
            throw new BusinessException(RestaurantErrorCode.NOT_FOUND);
        }
        validateSubmission(request, target.getCategoryCode());
        return save(id, request, null);
    }

    /** 新店投稿不创建公开餐厅，仅保存无正式目标ID的待审申请，分类必须当前可用。 */
    @Transactional
    public String submitNew(RestaurantChangeRequest request) {
        if (!validator.validate(request).isEmpty()) {
            throw new BusinessException(RestaurantErrorCode.INVALID_INPUT);
        }
        validateSubmission(request, null);
        return save(null, request, null);
    }

    /**
     * 新店和可选点评在同一事务保存。店铺快照不含点评，联合摘要另纳入规范化点评及附图， 因此同UUID增加、删除或修改点评均会冲突；无点评继续使用原摘要以兼容旧成功请求。
     * RC配合申请唯一键及行锁串行化重试，避免不同新申请在空点评索引上相互持有间隙锁。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RestaurantSubmissionVo submitNewWithReview(RestaurantSubmissionRequest request) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new BusinessException(RestaurantErrorCode.INVALID_INPUT);
        }
        validateSubmission(request, null);
        String uuid = request.getClientRequestId().toString();
        // 食堂只能评价具体窗口，联合提交不能绕过这条现行规则；普通餐厅停业仍沿用可评规则。
        if (request.getReview() != null && !"REGULAR".equals(request.getRestaurantType())) {
            throw new BusinessException(RestaurantErrorCode.INVALID_INPUT, "仅普通餐厅可以附带点评，食堂请评价具体窗口");
        }
        String reviewHash =
                request.getReview() == null
                        ? null
                        : reviewSubmissions.fingerprint(uuid, request.getReview());
        String id = save(null, request, null, reviewHash);
        var result = new RestaurantSubmissionVo();
        result.setId(id);
        result.setStatus("PENDING");
        if (request.getReview() != null) {
            result.setReviewSubmission(
                    reviewSubmissions.saveWaiting(Long.parseLong(id), uuid, request.getReview()));
        }
        return result;
    }

    /**
     * 驳回后重提。
     *
     * <p>仅原申请人可以基于自己的被驳回申请创建新申请；原申请保持REJECTED及原驳回原因不变，
     * 新申请写入previous_request_id并在同一事务完成内容校验、目标校验和图片绑定。 非驳回状态统一409，不能借重提恢复已撤回或已通过的申请。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String resubmit(String previousId, RestaurantSubmissionRequest request) {
        if (!validator.validate(request).isEmpty()) {
            throw new BusinessException(RestaurantErrorCode.INVALID_INPUT);
        }
        long owner = actors.requireActor().getUserId();
        long parentId = RestaurantIdentifiers.id(previousId);
        var hint = mapper.ownHint(parentId, owner);
        if (hint == null) {
            throw new BusinessException(RestaurantErrorCode.REQUEST_NOT_FOUND);
        }
        RestaurantEntity target =
                "UPDATE".equals(hint.getRequestType())
                        ? mapper.lockTarget(hint.getRestaurantId())
                        : null;
        var previous = mapper.lockOwn(parentId, owner);
        // 不区分他人申请与不存在，避免用申请ID探测他人记录。
        if (previous == null) {
            throw new BusinessException(RestaurantErrorCode.REQUEST_NOT_FOUND);
        }
        if (!"REJECTED".equals(previous.getStatus())) {
            throw new BusinessException(RestaurantErrorCode.STATE_CONFLICT, "仅被驳回申请可以重新提交");
        }
        // 先锁原申请再锁后续申请，重放在触碰点评前返回；防止恢复迁移前内容或与子申请编辑反向加锁。
        var successor = mapper.successor(parentId, owner);
        if (successor != null
                && !successor
                        .getClientRequestId()
                        .equalsIgnoreCase(request.getClientRequestId().toString())) {
            throw new BusinessException(RestaurantErrorCode.STATE_CONFLICT, "该申请已重新提交，请查看后续申请");
        }
        String reviewHash =
                request.getReview() == null
                        ? null
                        : reviewSubmissions.fingerprint(
                                request.getClientRequestId().toString(), request.getReview());
        String next;
        if ("CREATE".equals(previous.getRequestType())) {
            validateSubmission(request, null);
            next = save(null, request, parentId, reviewHash);
        } else {
            if (request.getReview() != null) {
                throw new BusinessException(RestaurantErrorCode.INVALID_INPUT, "纠错申请不能附带点评");
            }
            if (target == null) {
                throw new BusinessException(RestaurantErrorCode.NOT_FOUND);
            }
            validateSubmission(request, target.getCategoryCode());
            next = save(previous.getRestaurantId(), request, parentId);
        }
        if (successor == null) {
            reviewLifecycle.resubmit(
                    parentId,
                    Long.parseLong(next),
                    request.getClientRequestId().toString(),
                    previous.getRequestType(),
                    request.getRestaurantType(),
                    request.getReview());
        }
        return next;
    }

    /** 新食堂申请必须无分类；已有旧申请允许按原内容重试，不能因分类规则升级改写其摘要。 此处只放行到后面的完整摘要/目标比较，不允许借旧请求编号新增不符合规则的申请。 */
    private void validateSubmission(RestaurantChangeRequest request, String currentCategory) {
        if ("CANTEEN".equals(request.getRestaurantType())
                && request.getCategoryCode() != null
                && mapper.existingRequest(
                                actors.requireActor().getUserId(),
                                request.getClientRequestId().toString())
                        != null) {
            validation.normalizeChange(request);
            return;
        }
        validation.validateChange(request, currentCategory);
    }

    /** 唯一键原子插入或取得旧回执，再锁定核对内容；避免首次并发投稿的检查再插入竞争。 */
    private String save(Long targetId, RestaurantChangeRequest request, Long previousRequestId) {
        return save(targetId, request, previousRequestId, null);
    }

    /** 附带点评只影响联合提交摘要，不进入管理员可修改的店铺业务快照。 */
    private String save(
            Long targetId,
            RestaurantChangeRequest request,
            Long previousRequestId,
            String reviewHash) {
        var actor = actors.requireActor();
        // 类型来自服务端调用入口，不接收客户端自报；审核回填新商户ID后也不能改变申请类型。
        String requestType = targetId == null ? "CREATE" : "UPDATE";
        // ID按long范围校验并保留用户顺序，重复图片会破坏独占绑定，须在写入前拒绝。
        java.util.List<Long> photoIds;
        try {
            photoIds = request.getPhotoIds().stream().map(Long::parseLong).toList();
        } catch (NumberFormatException invalid) {
            throw new BusinessException(RestaurantErrorCode.INVALID_INPUT);
        }
        if (photoIds.stream().distinct().count() != photoIds.size()) {
            throw new BusinessException(RestaurantErrorCode.INVALID_INPUT);
        }
        String payload = json.writeValueAsString(request);
        byte[] hash = RestaurantSubmissionHashPolicy.hash(payload, reviewHash);
        var row = new RestaurantChangeEntity();
        row.setRestaurantId(targetId);
        row.setPreviousRequestId(previousRequestId);
        row.setSubmittedBy(actor.getUserId());
        row.setRequestType(requestType);
        row.setClientRequestId(request.getClientRequestId().toString());
        row.setRequestHash(hash);
        row.setProposedData(payload);
        mapper.insert(row);
        var saved = mapper.findRequest(actor.getUserId(), request.getClientRequestId().toString());
        // 新店审核后restaurant_id会从NULL变为新ID；只比较原申请类型和原始内容，不把发布结果误判成换目标。
        // 纠错仍比较原商户ID，防止相同请求编号被复用于另一家店；原始摘要不随管理员修订而更新。
        boolean sameTarget =
                "CREATE".equals(requestType) || Objects.equals(saved.getRestaurantId(), targetId);
        // 重提回执还必须指向同一条被驳回申请，防止复用请求UUID把首次投稿误认成重提。
        boolean samePrevious = Objects.equals(saved.getPreviousRequestId(), previousRequestId);
        if (!Objects.equals(saved.getRequestType(), requestType)
                || !sameTarget
                || !samePrevious
                || !MessageDigest.isEqual(saved.getRequestHash(), hash)) {
            throw new BusinessException(RestaurantErrorCode.STATE_CONFLICT);
        }
        // insert返回的自增ID不用于判断首次写入：MySQL防重返回旧ID，读取关联才是可靠依据。
        var existingPhotos = mapper.photoIds(saved.getId());
        if (!existingPhotos.isEmpty()) {
            if (!existingPhotos.equals(photoIds)) {
                throw new BusinessException(RestaurantErrorCode.STATE_CONFLICT);
            }
        } else if (!photoIds.isEmpty()) {
            media.bindRestaurantPhotos(photoIds, actor.getUserId());
            for (int position = 0; position < photoIds.size(); position++) {
                mapper.insertPhoto(saved.getId(), position, photoIds.get(position));
            }
        }
        return saved.getId().toString();
    }
}
