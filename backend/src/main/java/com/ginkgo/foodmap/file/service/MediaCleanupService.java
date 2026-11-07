package com.ginkgo.foodmap.file.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.file.entity.MediaFile;
import com.ginkgo.foodmap.file.exception.FileErrorCode;
import com.ginkgo.foodmap.file.mapper.MediaFileMapper;
import com.ginkgo.foodmap.file.storage.OssStorageClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 主动删除与后台回收共用实现：先提交删除意图，云请求期间不持有数据库锁。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaCleanupService {
    private final MediaFileMapper files;
    private final OssStorageClient storage;
    private final PlatformTransactionManager transactions;

    /** 本人取消立即尝试删除；false表示意图已提交但云端完成尚未确认。 */
    public boolean cancel(long id, long owner) {
        var row =
                tx().execute(
                                s -> {
                                    var file = files.lock(id);
                                    if (file == null) {
                                        return null;
                                    }
                                    if (file.getUploaderUserId() != owner) {
                                        throw new BusinessException(FileErrorCode.NOT_FOUND);
                                    }
                                    if ("BOUND".equals(file.getStatus())) {
                                        throw new BusinessException(FileErrorCode.BOUND);
                                    }
                                    files.markPending(id);
                                    return file;
                                });
        return row == null || deletePending(row);
    }

    /** 上传失败后的补偿不能覆盖BOUND状态，不能用清理失败掩盖原始上传错误。 */
    public void uploadFailed(long id) {
        try {
            var row =
                    tx().execute(
                                    s -> {
                                        var file = files.lock(id);
                                        if (file == null || "BOUND".equals(file.getStatus())) {
                                            return null;
                                        }
                                        files.markPending(id);
                                        return file;
                                    });
            if (row != null) {
                deletePending(row);
            }
        } catch (RuntimeException e) {
            log.warn("上传补偿未完成，等待到期回收；fileId={}", id);
        }
    }

    /** 批次内重新核验期限，只有不可逆的待删除状态才进行云端清理。 */
    public void cleanupExpired() {
        for (long id : files.expired()) {
            try {
                var row =
                        tx().execute(
                                        s -> {
                                            var file = files.lockExpired(id);
                                            if (file != null) {
                                                files.markPending(id);
                                            }
                                            return file;
                                        });
                if (row != null) {
                    deletePending(row);
                }
            } catch (RuntimeException e) {
                log.warn("媒体到期处理失败；fileId={}，异常类型={}", id, e.getClass().getSimpleName());
            }
        }
    }

    /** 重复删除安全；云端成功但数据库失败会留下记录供重试。Bucket须未启用版本控制。 */
    private boolean deletePending(MediaFile row) {
        try {
            storage.delete(row.getBucketName(), row.getStorageKey());
            // 上传超时可能存在晚到写入：保护期内即使本次OSS删除成功也保留凭据，到期再删一次。
            return Boolean.TRUE.equals(
                    tx().execute(
                                    s ->
                                            files.delete(row.getId()) == 1
                                                    || files.find(row.getId()) == null));
        } catch (RuntimeException e) {
            log.warn("媒体删除未确认，保留重试；fileId={}，异常类型={}", row.getId(), e.getClass().getSimpleName());
            return false;
        }
    }

    private TransactionTemplate tx() {
        var tx = new TransactionTemplate(transactions);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
        return tx;
    }
}
