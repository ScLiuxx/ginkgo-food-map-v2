package com.foodmap.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.file.storage.CosStorageClient;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectInputStream;
import com.qcloud.cos.model.PutObjectRequest;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.io.ByteArrayInputStream;

/** 全部使用模拟SDK，不读本地云凭证、不访问真实COS。 */
class CosStorageClientTest {
    private final COSClient client = mock(COSClient.class);

    private CosStorageClient storage() {
        var factory = new DefaultListableBeanFactory();
        factory.registerSingleton("cos", client);
        return new CosStorageClient(factory.getBeanProvider(COSClient.class));
    }

    @Test
    void disabledStorageAndCloudFailureReturnOnlySafeBusinessErrors() {
        var factory = new DefaultListableBeanFactory();
        assertThatThrownBy(
                        new CosStorageClient(factory.getBeanProvider(COSClient.class))
                                ::requireAvailable)
                .isInstanceOf(BusinessException.class);
        doThrow(new IllegalStateException("fixture-secret-and-signature"))
                .when(client)
                .deleteObject(anyString(), anyString());
        assertThatThrownBy(() -> storage().delete("bucket", "key"))
                .isInstanceOf(BusinessException.class)
                .hasMessageNotContaining("fixture-secret-and-signature");
    }

    @Test
    void uploadPreservesBytesAndJpegMetadataWithoutChangingBucketAcl() throws Exception {
        var bytes = new byte[] {1, 2, 3};
        storage().put("bucket", "avatars/fixture.jpg", bytes);
        var capture = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(capture.capture());
        var request = capture.getValue();
        assertThat(request.getBucketName()).isEqualTo("bucket");
        assertThat(request.getKey()).isEqualTo("avatars/fixture.jpg");
        assertThat(request.getMetadata().getContentType()).isEqualTo("image/jpeg");
        assertThat(request.getMetadata().getContentLength()).isEqualTo(bytes.length);
        assertThat(request.getInputStream().readAllBytes()).isEqualTo(bytes);
        assertThat(request.getCannedAcl()).isNull();
        assertThat(request.getAccessControlList()).isNull();
    }

    @Test
    void readingIsBoundedAndAlwaysClosesTheCloudResponse() throws Exception {
        for (int size : new int[] {0, 3, 300 * 1024, 300 * 1024 + 1}) {
            var object = mock(COSObject.class);
            var input = new COSObjectInputStream(new ByteArrayInputStream(new byte[size]), null);
            when(object.getObjectContent()).thenReturn(input);
            when(client.getObject("bucket", "key")).thenReturn(object);
            if (size == 0 || size > 300 * 1024) {
                assertThatThrownBy(() -> storage().read("bucket", "key"))
                        .isInstanceOf(BusinessException.class);
            } else {
                assertThat(storage().read("bucket", "key")).hasSize(size);
            }
            verify(object).close();
        }
    }
}
