package com.ginkgo.foodmap.file.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.file.exception.FileErrorCode;
import com.ginkgo.foodmap.file.mapper.MediaFileMapper;
import com.ginkgo.foodmap.file.storage.OssStorageClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** 文件模块公开入口：负责媒体归属与状态，不访问点评表，不向其他模块暴露持久化实体。 */
@Service
@RequiredArgsConstructor
public class MediaFileService {
    private final MediaFileMapper files;
    private final OssStorageClient storage;
    private final com.ginkgo.foodmap.security.service.CurrentActorService actors;

    /** restaurant服务先核对本人申请/位置；文件侧再以真实身份校验上传者、用途及BOUND状态。 */
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public byte[] readOwnRestaurantBound(long id) {
        return readRestaurantFile(id, actors.requireActor().getUserId());
    }

    /** 管理员查看已核对申请关联的商户图；拒绝其他用途、其他上传者及未绑定文件。 */
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public byte[] readRestaurantBound(long id, long owner) {
        return readRestaurantFile(id, owner);
    }

    /** 仅在restaurant服务核对可见商户/正式相册/已通过来源后调用；没有按文件ID公开的HTTP入口。 */
    public byte[] readPublishedRestaurant(long id, long owner) {
        return readRestaurantFile(id, owner);
    }

    private byte[] readRestaurantFile(long id, long owner) {
        var row = files.find(id);
        if (row == null || !"BOUND".equals(row.getStatus()) || !"RESTAURANT".equals(row.getPurpose())
                || row.getUploaderUserId() != owner) throw new BusinessException(FileErrorCode.NOT_FOUND);
        return storage.read(row.getBucketName(), row.getStorageKey());
    }

    /** 审核事务内校验已绑定文件，不再次绑定、不访问OSS；升序锁定防止与文件状态变更交错。 */
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @Transactional(propagation=Propagation.MANDATORY)
    public void validateRestaurantPublication(List<Long> ids, long owner) {
        if (ids == null || ids.size() > 5 || ids.stream().distinct().count() != ids.size())
            throw new BusinessException(FileErrorCode.NOT_BINDABLE);
        for (long id : ids.stream().sorted().toList()) {
            var row = files.lock(id);
            if (row == null || !"BOUND".equals(row.getStatus()) || !"RESTAURANT".equals(row.getPurpose())
                    || row.getUploaderUserId() != owner) throw new BusinessException(FileErrorCode.NOT_BINDABLE);
        }
    }

    /** 加入点评事务，固定ID升序加锁；展示顺序由review关联表保留。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void bind(List<Long> ids,long owner) {
        bindPurpose(ids, owner, "REVIEW");
    }

    /** 商户申请服务先校验申请归属；加入申请事务，任一图片不合法时全部回滚。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void bindRestaurantPhotos(List<Long> ids, long owner) {
        bindPurpose(ids, owner, "RESTAURANT");
    }

    private void bindPurpose(List<Long> ids, long owner, String purpose) {
        for(long id:ids.stream().sorted().toList()) {
            var row=files.lock(id);
            if(row==null || row.getUploaderUserId()!=owner || !purpose.equals(row.getPurpose()) || files.bind(id,owner)!=1)
                throw new BusinessException(FileErrorCode.NOT_BINDABLE);
        }
    }

    /** 调用方先验证点评归属；和删评同事务登记清理，失败时关联与文件状态一起回滚。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void releaseBound(List<Long> ids, long owner) {
        for (long id : ids.stream().sorted().toList()) {
            if (files.releaseBound(id, owner) != 1)
                throw new IllegalStateException("Missing bound review media");
        }
    }

    /** 仅在调用方已验证点评可见性与关联后读取；不提供按文件ID公开HTTP访问。 */
    public byte[] readBound(long id) {
        var row=files.find(id);
        if(row==null || !"BOUND".equals(row.getStatus()) || !"REVIEW".equals(row.getPurpose()))
            throw new BusinessException(FileErrorCode.NOT_FOUND);
        return storage.read(row.getBucketName(),row.getStorageKey());
    }
}
