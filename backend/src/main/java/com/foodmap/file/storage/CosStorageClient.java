package com.foodmap.file.storage;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.file.exception.FileErrorCode;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

/** COS边界不暴露云端异常或签名；图片读取仍由业务服务先核对可见性。 */
@Component
@RequiredArgsConstructor
public class CosStorageClient {
    private final ObjectProvider<COSClient> clients;

    public void requireAvailable() {
        if (clients.getIfAvailable() == null) {
            throw new BusinessException(FileErrorCode.UNAVAILABLE);
        }
    }

    /** 不修改对象ACL，继承私有桶权限；应用身份无需PutObjectACL或桶管理权限。 */
    public void put(String bucket, String key, byte[] bytes) {
        requireAvailable();
        try {
            var metadata = new ObjectMetadata();
            metadata.setContentType("image/jpeg");
            metadata.setContentLength(bytes.length);
            clients.getObject()
                    .putObject(
                            new PutObjectRequest(
                                    bucket, key, new ByteArrayInputStream(bytes), metadata));
        } catch (RuntimeException failure) {
            throw new BusinessException(FileErrorCode.UNAVAILABLE);
        }
    }

    /** 未开启版本控制的桶中，删除不存在对象也成功；不访问旧OSS对象。 */
    public void delete(String bucket, String key) {
        requireAvailable();
        try {
            clients.getObject().deleteObject(bucket, key);
        } catch (RuntimeException failure) {
            throw new BusinessException(FileErrorCode.UNAVAILABLE);
        }
    }

    public byte[] read(String bucket, String key) {
        requireAvailable();
        try (var object = clients.getObject().getObject(bucket, key)) {
            var bytes = object.getObjectContent().readNBytes(300 * 1024 + 1);
            if (bytes.length == 0 || bytes.length > 300 * 1024) {
                throw new IllegalStateException("Invalid stored image size");
            }
            return bytes;
        } catch (Exception failure) {
            throw new BusinessException(FileErrorCode.UNAVAILABLE);
        }
    }
}
