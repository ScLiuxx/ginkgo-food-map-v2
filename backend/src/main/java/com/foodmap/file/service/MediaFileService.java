package com.foodmap.file.service;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.file.exception.FileErrorCode;
import com.foodmap.file.mapper.MediaFileMapper;
import com.foodmap.file.storage.OssStorageClient;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Stream;

/** 文件模块公开入口：负责媒体归属与状态，不访问点评表，不向其他模块暴露持久化实体。 */
@Service
@RequiredArgsConstructor
public class MediaFileService {
    private final MediaFileMapper files;
    private final OssStorageClient storage;
    private final com.foodmap.security.service.CurrentActorService actors;

    /** 用户服务先锁本人用户行；文件按升序锁定，绑定新图和释放旧图随资料事务一起回滚。 */
    @PreAuthorize("isAuthenticated()")
    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceAvatar(Long existing, long replacement) {
        long owner = actors.requireActor().getUserId();
        var ids =
                Stream.of(existing, replacement)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .sorted()
                        .toList();
        for (long id : ids) {
            var row = files.lock(id);
            if (row == null
                    || row.getUploaderUserId() != owner
                    || !"AVATAR".equals(row.getPurpose())) {
                throw new BusinessException(FileErrorCode.NOT_BINDABLE);
            }
            if (java.util.Objects.equals(existing, id)) {
                if (!"BOUND".equals(row.getStatus())
                        || (id != replacement && files.releaseBound(id, owner) != 1)) {
                    throw new BusinessException(FileErrorCode.NOT_BINDABLE);
                }
            } else if (files.bind(id, owner) != 1) {
                throw new BusinessException(FileErrorCode.NOT_BINDABLE);
            }
        }
    }

    /** 用户服务已核对当前头像关联和账号可见性；没有任意文件ID读取入口，OSS请求不加入写事务。 */
    public byte[] readAvatar(long id, long owner) {
        var row = files.find(id);
        if (row == null
                || row.getUploaderUserId() != owner
                || !"AVATAR".equals(row.getPurpose())
                || !"BOUND".equals(row.getStatus())) {
            throw new BusinessException(FileErrorCode.NOT_FOUND);
        }
        return storage.read(row.getBucketName(), row.getStorageKey());
    }

    /** restaurant服务先核对本人申请/位置；文件侧再以真实身份校验上传者、用途及BOUND状态。 */
    @org.springframework.security.access.prepost.PreAuthorize("isAuthenticated()")
    public byte[] readOwnRestaurantBound(long id) {
        return readRestaurantFile(id, actors.requireActor().getUserId());
    }

    /** 待发布点评图片只允许上传者读取；调用方还必须校验申请及点评关联，不能复用正式点评的公开读取。 */
    @PreAuthorize("isAuthenticated()")
    public byte[] readOwnReviewBound(long id) {
        long owner = actors.requireActor().getUserId();
        var row = files.find(id);
        if (row == null
                || !"BOUND".equals(row.getStatus())
                || !"REVIEW".equals(row.getPurpose())
                || row.getUploaderUserId() != owner) {
            throw new BusinessException(FileErrorCode.NOT_FOUND);
        }
        return storage.read(row.getBucketName(), row.getStorageKey());
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
        if (row == null
                || !"BOUND".equals(row.getStatus())
                || !"RESTAURANT".equals(row.getPurpose())
                || row.getUploaderUserId() != owner)
            throw new BusinessException(FileErrorCode.NOT_FOUND);
        return storage.read(row.getBucketName(), row.getStorageKey());
    }

    /** 审核事务内校验已绑定文件，不再次绑定、不访问OSS；升序锁定防止与文件状态变更交错。 */
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @Transactional(propagation = Propagation.MANDATORY)
    public void validateRestaurantPublication(List<Long> ids, long owner) {
        if (ids == null || ids.size() > 5 || ids.stream().distinct().count() != ids.size())
            throw new BusinessException(FileErrorCode.NOT_BINDABLE);
        for (long id : ids.stream().sorted().toList()) {
            var row = files.lock(id);
            if (row == null
                    || !"BOUND".equals(row.getStatus())
                    || !"RESTAURANT".equals(row.getPurpose())
                    || row.getUploaderUserId() != owner)
                throw new BusinessException(FileErrorCode.NOT_BINDABLE);
        }
    }

    /** 审核自动发布只转移已绑定的本人REVIEW图片；按ID升序锁定，不重新绑定、不复制或读取OSS。 */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(propagation = Propagation.MANDATORY)
    public void validateReviewPublication(List<Long> ids, long owner) {
        if (ids == null || ids.size() > 5 || ids.stream().distinct().count() != ids.size()) {
            throw new BusinessException(FileErrorCode.NOT_BINDABLE);
        }
        for (long id : ids.stream().sorted().toList()) {
            var row = files.lock(id);
            if (row == null
                    || !"BOUND".equals(row.getStatus())
                    || !"REVIEW".equals(row.getPurpose())
                    || row.getUploaderUserId() != owner) {
                throw new BusinessException(FileErrorCode.NOT_BINDABLE);
            }
        }
    }

    /** 加入点评事务，固定ID升序加锁；展示顺序由review关联表保留。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void bind(List<Long> ids, long owner) {
        bindPurpose(ids, owner, "REVIEW");
    }

    /**
     * 本人待发布点评替换图片：review服务已锁点评并确认原关联，本服务按并集ID升序处理避免反序加锁。
     * 保留图片校验BOUND归属和用途；仅新增图片要求有效临时状态，移除图片登记清理，均不访问OSS。
     */
    @PreAuthorize("isAuthenticated()")
    @Transactional(propagation = Propagation.MANDATORY)
    public void replaceOwnReviewPhotos(List<Long> existing, List<Long> replacement) {
        long owner = actors.requireActor().getUserId();
        if (existing == null
                || replacement == null
                || existing.size() > 5
                || replacement.size() > 5
                || existing.stream().distinct().count() != existing.size()
                || replacement.stream().distinct().count() != replacement.size()) {
            throw new BusinessException(FileErrorCode.NOT_BINDABLE);
        }
        var ids =
                Stream.concat(existing.stream(), replacement.stream()).distinct().sorted().toList();
        for (long id : ids) {
            var row = files.lock(id);
            if (row == null
                    || row.getUploaderUserId() != owner
                    || !"REVIEW".equals(row.getPurpose())) {
                throw new BusinessException(FileErrorCode.NOT_BINDABLE);
            }
            if (existing.contains(id)) {
                if (!"BOUND".equals(row.getStatus())) {
                    throw new BusinessException(FileErrorCode.NOT_BINDABLE);
                }
                if (!replacement.contains(id) && files.releaseBound(id, owner) != 1) {
                    throw new BusinessException(FileErrorCode.NOT_BINDABLE);
                }
            } else if (files.bind(id, owner) != 1) {
                throw new BusinessException(FileErrorCode.NOT_BINDABLE);
            }
        }
    }

    /** 商户申请服务先校验申请归属；加入申请事务，任一图片不合法时全部回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void bindRestaurantPhotos(List<Long> ids, long owner) {
        bindPurpose(ids, owner, "RESTAURANT");
    }

    /**
     * 本人修改待审申请替换附图时释放旧图。
     *
     * <p>只允许释放本人上传且仍为RESTAURANT用途的已绑定图片；登记删除意图后由既有回收任务清理， 与申请修改处于同一事务，替换失败时一并回滚。调用方必须先核对申请归属和待审状态。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseRestaurantPhotos(List<Long> ids, long owner) {
        for (long id : ids.stream().sorted().toList()) {
            var row = files.lock(id);
            if (row == null
                    || row.getUploaderUserId() != owner
                    || !"RESTAURANT".equals(row.getPurpose())
                    || !"BOUND".equals(row.getStatus())
                    || files.releaseBound(id, owner) != 1) {
                throw new BusinessException(FileErrorCode.NOT_BINDABLE);
            }
        }
    }

    /** 窗口服务先锁父食堂和窗口；仅允许绑定当前管理员本人上传的WINDOW用途图片。 */
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @Transactional(propagation = Propagation.MANDATORY)
    public void bindWindowPhoto(long id, long owner) {
        bindPurpose(List.of(id), owner, "WINDOW");
    }

    /** 窗口服务已核对关联和管理员权限；允许其他管理员删除旧图，不改变上传者记录。 */
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseWindowPhoto(long id) {
        var row = files.lock(id);
        if (row == null
                || !"WINDOW".equals(row.getPurpose())
                || !"BOUND".equals(row.getStatus())
                || files.releaseBound(id, row.getUploaderUserId()) != 1) {
            throw new BusinessException(FileErrorCode.NOT_BINDABLE);
        }
    }

    /** 仅供已核对窗口及父食堂可见性、相册关联的服务调用，删除后立即停止读取。 */
    public byte[] readWindowPhoto(long id) {
        var row = files.find(id);
        if (row == null || !"BOUND".equals(row.getStatus()) || !"WINDOW".equals(row.getPurpose())) {
            throw new BusinessException(FileErrorCode.NOT_FOUND);
        }
        return storage.read(row.getBucketName(), row.getStorageKey());
    }

    private void bindPurpose(List<Long> ids, long owner, String purpose) {
        for (long id : ids.stream().sorted().toList()) {
            var row = files.lock(id);
            if (row == null
                    || row.getUploaderUserId() != owner
                    || !purpose.equals(row.getPurpose())
                    || files.bind(id, owner) != 1)
                throw new BusinessException(FileErrorCode.NOT_BINDABLE);
        }
    }

    /** 调用方先验证点评归属；和删评同事务登记清理，失败时关联与文件状态一起回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseBound(List<Long> ids, long owner) {
        for (long id : ids.stream().sorted().toList()) {
            if (files.releaseBound(id, owner) != 1)
                throw new IllegalStateException("Missing bound review media");
        }
    }

    /** 仅在调用方已验证点评可见性与关联后读取；不提供按文件ID公开HTTP访问。 */
    public byte[] readBound(long id) {
        var row = files.find(id);
        if (row == null || !"BOUND".equals(row.getStatus()) || !"REVIEW".equals(row.getPurpose()))
            throw new BusinessException(FileErrorCode.NOT_FOUND);
        return storage.read(row.getBucketName(), row.getStorageKey());
    }
}
