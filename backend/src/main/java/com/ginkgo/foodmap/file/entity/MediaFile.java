package com.ginkgo.foodmap.file.entity;

import lombok.Data;
import java.time.Instant;

/** 文件模块私有持久化对象，不向客户端暴露Bucket和Object Key。 */
@Data
public class MediaFile {
    private Long id;
    private long uploaderUserId;
    private String bucketName;
    private String storageKey;
    private int byteSize;
    private int width;
    private int height;
    private String sha256;
    private String status;
    private String purpose;
    private Instant expiresAt;
}
