package com.ginkgo.foodmap.restaurant.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.restaurant.dto.RestaurantChangeRequest;
import com.ginkgo.foodmap.restaurant.entity.RestaurantChangeEntity;
import com.ginkgo.foodmap.restaurant.exception.RestaurantErrorCode;
import com.ginkgo.foodmap.restaurant.mapper.RestaurantChangeMapper;
import com.ginkgo.foodmap.restaurant.support.RestaurantIdentifiers;
import com.ginkgo.foodmap.security.service.CurrentActorService;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
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
    private final com.ginkgo.foodmap.file.service.MediaFileService media;

    /** 返回申请ID；相同账号/请求ID/规范化内容重试返回同一申请，不重复创建。 */
    @Transactional
    public String submit(String targetId, RestaurantChangeRequest request) {
        if (!validator.validate(request).isEmpty()) throw new BusinessException(RestaurantErrorCode.INVALID_INPUT);
        long id = RestaurantIdentifiers.id(targetId);
        var target = mapper.lockTarget(id);
        if (target == null) throw new BusinessException(RestaurantErrorCode.NOT_FOUND);
        validation.validateChange(request, target.getCategoryCode());
        return save(id, request);
    }

    /** 新店投稿不创建公开餐厅，仅保存无正式目标ID的待审申请，分类必须当前可用。 */
    @Transactional
    public String submitNew(RestaurantChangeRequest request) {
        if (!validator.validate(request).isEmpty()) throw new BusinessException(RestaurantErrorCode.INVALID_INPUT);
        validation.validateChange(request, null);
        return save(null, request);
    }

    /** 唯一键原子插入或取得旧回执，再锁定核对内容；避免首次并发投稿的检查再插入竞争。 */
    private String save(Long targetId, RestaurantChangeRequest request) {
        var actor = actors.requireActor();
        // ID按long范围校验并保留用户顺序，重复图片会破坏独占绑定，须在写入前拒绝。
        java.util.List<Long> photoIds;
        try { photoIds = request.getPhotoIds().stream().map(Long::parseLong).toList(); }
        catch (NumberFormatException invalid) { throw new BusinessException(RestaurantErrorCode.INVALID_INPUT); }
        if (photoIds.stream().distinct().count() != photoIds.size())
            throw new BusinessException(RestaurantErrorCode.INVALID_INPUT);
        String payload = json.writeValueAsString(request);
        byte[] hash;
        try { hash = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        var row = new RestaurantChangeEntity();
        row.setRestaurantId(targetId); row.setSubmittedBy(actor.getUserId());
        row.setClientRequestId(request.getClientRequestId().toString());
        row.setRequestHash(hash); row.setProposedData(payload);
        mapper.insert(row);
        var saved = mapper.findRequest(actor.getUserId(), request.getClientRequestId().toString());
        if (!Objects.equals(saved.getRestaurantId(), targetId) || !MessageDigest.isEqual(saved.getRequestHash(), hash))
            throw new BusinessException(RestaurantErrorCode.STATE_CONFLICT);
        // insert返回的自增ID不用于判断首次写入：MySQL防重返回旧ID，读取关联才是可靠依据。
        var existingPhotos = mapper.photoIds(saved.getId());
        if (!existingPhotos.isEmpty()) {
            if (!existingPhotos.equals(photoIds)) throw new BusinessException(RestaurantErrorCode.STATE_CONFLICT);
        } else if (!photoIds.isEmpty()) {
            media.bindRestaurantPhotos(photoIds, actor.getUserId());
            for (int position = 0; position < photoIds.size(); position++)
                mapper.insertPhoto(saved.getId(), position, photoIds.get(position));
        }
        return saved.getId().toString();
    }
}
