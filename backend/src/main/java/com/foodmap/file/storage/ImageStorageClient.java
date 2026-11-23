package com.foodmap.file.storage;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.file.config.CosProperties;
import com.foodmap.file.config.OssProperties;
import com.foodmap.file.exception.FileErrorCode;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 新上传优先已开启的COS；读取和删除严格按已登记桶名路由，不尝试跨云兜底。 */
@Component
@RequiredArgsConstructor
public class ImageStorageClient {
    private final OssStorageClient oss;
    private final CosStorageClient cos;
    private final OssProperties ossProperties;
    private final CosProperties cosProperties;

    /** 禁用的旧桶不参加扫描，避免旧记录占满批次而阻塞当前桶的回收。 */
    public List<String> cleanupBuckets() {
        var buckets = new ArrayList<String>();
        if (cosProperties.isEnabled()) {
            buckets.add(cosProperties.getBucket());
        }
        if (ossProperties.isEnabled()) {
            buckets.add(ossProperties.getBucket());
        }
        return List.copyOf(buckets);
    }

    public void requireAvailable() {
        if (cosProperties.isEnabled()) {
            cos.requireAvailable();
        } else if (ossProperties.isEnabled()) {
            oss.requireAvailable();
        } else {
            throw new BusinessException(FileErrorCode.UNAVAILABLE);
        }
    }

    public void put(String bucket, String key, byte[] bytes) {
        requireAvailable();
        if (cosProperties.isEnabled() && cosProperties.getBucket().equals(bucket)) {
            cos.put(bucket, key, bytes);
        } else if (!cosProperties.isEnabled()
                && ossProperties.isEnabled()
                && ossProperties.getBucket().equals(bucket)) {
            oss.put(bucket, key, bytes);
        } else {
            throw new BusinessException(FileErrorCode.UNAVAILABLE);
        }
    }

    public byte[] read(String bucket, String key) {
        if (isCos(bucket)) {
            return cos.read(bucket, key);
        }
        return oss.read(bucket, key);
    }

    public void delete(String bucket, String key) {
        if (isCos(bucket)) {
            cos.delete(bucket, key);
        } else {
            oss.delete(bucket, key);
        }
    }

    private boolean isCos(String bucket) {
        if (cosProperties.isEnabled() && cosProperties.getBucket().equals(bucket)) {
            return true;
        }
        if (ossProperties.isEnabled() && ossProperties.getBucket().equals(bucket)) {
            return false;
        }
        // 未配置的旧桶不允许误删同名Key的新云对象，也不能擅自向当前桶请求。
        throw new BusinessException(FileErrorCode.UNAVAILABLE);
    }
}
