package com.ginkgo.foodmap.file.service;

import com.ginkgo.foodmap.file.storage.OssStorageClient;

import com.ginkgo.foodmap.file.image.JpegImageProcessor;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.file.config.OssProperties;
import com.ginkgo.foodmap.file.entity.MediaFile;
import com.ginkgo.foodmap.file.exception.FileErrorCode;
import com.ginkgo.foodmap.file.mapper.MediaFileMapper;
import com.ginkgo.foodmap.file.vo.ImageUploadVo;
import com.ginkgo.foodmap.security.service.CurrentActorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.io.InputStream;
import java.util.UUID;

/** 上传独立于点评事务：短事务登记、事务外OSS上传、短事务激活。 */
@Service
@RequiredArgsConstructor
public class ImageUploadService {
    private final CurrentActorService actors;
    private final JpegImageProcessor policy;
    private final OssStorageClient storage;
    private final OssProperties properties;
    private final MediaFileMapper files;
    private final PlatformTransactionManager transactions;
    private final MediaCleanupService cleanup;

    /** 预登记保证云端超时或进程崩溃时仍有持久化清理凭据；失败请求不返回可绑定ID。 */
    public ImageUploadVo upload(InputStream source) {
        return upload(source, "REVIEW");
    }

    /** 用途仅接受服务端白名单，路径由配置生成；商户与点评共用私有OSS及临时回收流程。 */
    public ImageUploadVo upload(InputStream source, String purpose) {
        if (!"REVIEW".equals(purpose) && !"RESTAURANT".equals(purpose))
            throw new BusinessException(FileErrorCode.INVALID_IMAGE);
        long owner = actors.requireActor().getUserId();
        storage.requireAvailable();
        var image = policy.process(source);
        var file = new MediaFile();
        file.setUploaderUserId(owner); file.setBucketName(properties.getBucket());
        file.setPurpose(purpose);
        String prefix = "RESTAURANT".equals(purpose) ? properties.getRestaurantPrefix() : properties.getPrefix();
        file.setStorageKey(prefix+UUID.randomUUID()+".jpg");
        file.setByteSize(image.getBytes().length);file.setWidth(image.getWidth());file.setHeight(image.getHeight());
        file.setSha256(image.getSha256());
        tx().executeWithoutResult(s -> files.reserve(file));
        MediaFile result;
        try {
          storage.put(file.getBucketName(),file.getStorageKey(),image.getBytes());
          result = tx().execute(s -> {
            if (files.activate(file.getId(),properties.getTemporaryTtl().toSeconds()) != 1)
                throw new BusinessException(FileErrorCode.UNAVAILABLE);
            return files.find(file.getId());
          });
        } catch(RuntimeException e) {
            cleanup.uploadFailed(file.getId());
            throw e;
        }
        var response = new ImageUploadVo();
        response.setFileId(result.getId().toString());response.setByteSize(result.getByteSize());
        response.setWidth(result.getWidth());response.setHeight(result.getHeight());response.setExpiresAt(result.getExpiresAt());
        return response;
    }

    /** 仅移除本人未绑定图片；已绑定返回409提醒核对发布结果，不存在视为幂等完成。 */
    public boolean cancel(String id) {
        long owner = actors.requireActor().getUserId();
        long fileId;
        try { if (id == null || !id.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();fileId=Long.parseLong(id); }
        catch (NumberFormatException e) { throw new BusinessException(FileErrorCode.NOT_FOUND); }
        return cleanup.cancel(fileId,owner);
    }

    /** 使用独立提交，防止调用者外部事务回滚丢失OSS清理凭据。 */
    private TransactionTemplate tx() {
        var template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setTimeout(10);
        return template;
    }
}
