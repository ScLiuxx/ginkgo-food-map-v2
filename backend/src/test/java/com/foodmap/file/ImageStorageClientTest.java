package com.foodmap.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.file.config.CosProperties;
import com.foodmap.file.config.OssProperties;
import com.foodmap.file.storage.CosStorageClient;
import com.foodmap.file.storage.ImageStorageClient;
import com.foodmap.file.storage.OssStorageClient;

import org.junit.jupiter.api.Test;

/** 相同Key可能存在两朵云，必须用已登记桶路由，不能失败后自动切换或误删。 */
class ImageStorageClientTest {
    private final OssStorageClient oss = mock(OssStorageClient.class);
    private final CosStorageClient cos = mock(CosStorageClient.class);
    private final OssProperties old = new OssProperties();
    private final CosProperties current = new CosProperties();
    private final ImageStorageClient storage = new ImageStorageClient(oss, cos, old, current);

    @Test
    void cosReceivesNewUploadsWhileOldBucketReadsStayExplicit() {
        old.setEnabled(true);
        old.setBucket("old-bucket");
        current.setEnabled(true);
        current.setBucket("new-bucket-1234567890");
        assertThat(storage.cleanupBuckets()).containsExactly(current.getBucket(), old.getBucket());
        var bytes = new byte[] {1};
        storage.put(current.getBucket(), "avatars/same.jpg", bytes);
        verify(cos).put(current.getBucket(), "avatars/same.jpg", bytes);
        when(oss.read(old.getBucket(), "avatars/same.jpg")).thenReturn(bytes);
        assertThat(storage.read(old.getBucket(), "avatars/same.jpg")).isEqualTo(bytes);
        storage.delete(current.getBucket(), "avatars/same.jpg");
        verify(cos).delete(current.getBucket(), "avatars/same.jpg");
        assertThatThrownBy(() -> storage.put(old.getBucket(), "key", bytes))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void disabledOrUnrecognizedBucketsNeverReachEitherCloud() {
        assertThat(storage.cleanupBuckets()).isEmpty();
        assertThatThrownBy(storage::requireAvailable).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> storage.read("old-bucket", "same.jpg"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> storage.delete("old-bucket", "same.jpg"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(oss, cos);
    }

    @Test
    void cleanupOnlyScansEnabledBuckets() {
        current.setEnabled(true);
        current.setBucket("new-bucket-1234567890");
        old.setBucket("old-bucket");
        assertThat(storage.cleanupBuckets()).containsExactly(current.getBucket());
        assertThatThrownBy(() -> storage.delete(old.getBucket(), "same.jpg"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(oss, cos);
    }

    @Test
    void ossRemainsUsableWhenCosIsNotEnabled() {
        old.setEnabled(true);
        old.setBucket("old-bucket");
        storage.put(old.getBucket(), "key", new byte[] {1});
        storage.delete(old.getBucket(), "key");
        verify(oss).delete(old.getBucket(), "key");
        verifyNoInteractions(cos);
    }

    @Test
    void cosFailureDoesNotFallbackToOss() {
        old.setEnabled(true);
        old.setBucket("old-bucket");
        current.setEnabled(true);
        current.setBucket("new-bucket-1234567890");
        when(cos.read(current.getBucket(), "key"))
                .thenThrow(
                        new BusinessException(
                                com.foodmap.file.exception.FileErrorCode.UNAVAILABLE));
        assertThatThrownBy(() -> storage.read(current.getBucket(), "key"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(oss);
    }
}
