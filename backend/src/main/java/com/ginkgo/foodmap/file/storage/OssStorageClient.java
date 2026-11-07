package com.ginkgo.foodmap.file.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.*;
import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.file.exception.FileErrorCode;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

/** 隔离SDK边界；客户端永不获得云凭证或OSS内部异常。 */
@Component
@RequiredArgsConstructor
public class OssStorageClient {
    private final ObjectProvider<OSS> clients;

    /** 未开启云配置时明确拒绝上传，不影响已存在的文本业务。 */
    public void requireAvailable() {
        if (clients.getIfAvailable() == null) {
            throw new BusinessException(FileErrorCode.UNAVAILABLE);
        }
    }

    /** 随机Key不会覆盖既有用户文件；显式私有ACL，不继承意外的公开对象配置。 */
    public void put(String bucket, String key, byte[] bytes) {
        requireAvailable();
        try {
            var metadata = new ObjectMetadata();
            metadata.setContentType("image/jpeg");
            metadata.setContentLength(bytes.length);
            metadata.setHeader("x-oss-object-acl", "private");
            clients.getObject().putObject(bucket, key, new ByteArrayInputStream(bytes), metadata);
        } catch (RuntimeException e) {
            throw new BusinessException(FileErrorCode.UNAVAILABLE);
        }
    }

    /** OSS DeleteObject对不存在对象同样成功，适合崩溃恢复后的重复清理。 */
    public void delete(String bucket, String key) {
        requireAvailable();
        try {
            clients.getObject().deleteObject(bucket, key);
        } catch (RuntimeException e) {
            throw new BusinessException(FileErrorCode.UNAVAILABLE);
        }
    }

    /** 有界读取私有JPEG，必须先由上层核验资源可见性；不签发可绕过后续校验的永久URL。 */
    public byte[] read(String bucket, String key) {
        requireAvailable();
        try (var object = clients.getObject().getObject(bucket, key)) {
            var bytes = object.getObjectContent().readNBytes(300 * 1024 + 1);
            if (bytes.length == 0 || bytes.length > 300 * 1024) {
                throw new IllegalStateException("Invalid stored image size");
            }
            return bytes;
        } catch (Exception e) {
            throw new BusinessException(FileErrorCode.UNAVAILABLE);
        }
    }
}
