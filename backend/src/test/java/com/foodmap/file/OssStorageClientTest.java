package com.foodmap.file;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.foodmap.common.exception.BusinessException;
import com.foodmap.file.storage.OssStorageClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 云适配层测试不连接外网，验证私有ACL及对外异常脱敏。 */
class OssStorageClientTest {
    @Test void disabledStorageFailsExplicitly() {
        var factory=new DefaultListableBeanFactory();
        var storage=new OssStorageClient(factory.getBeanProvider(OSS.class));
        assertThatThrownBy(storage::requireAvailable).isInstanceOf(BusinessException.class)
                .hasMessage("图片存储暂不可用，请稍后重试");
    }
    @Test void uploadForcesPrivateJpegMetadata() {
        OSS client=mock(OSS.class);
        var factory=new DefaultListableBeanFactory();factory.registerSingleton("oss",client);
        new OssStorageClient(factory.getBeanProvider(OSS.class)).put("bucket","key",new byte[]{1,2});
        var metadata=org.mockito.ArgumentCaptor.forClass(ObjectMetadata.class);
        verify(client).putObject(eq("bucket"),eq("key"),any(java.io.InputStream.class),metadata.capture());
        assertThat(metadata.getValue().getContentType()).isEqualTo("image/jpeg");
        assertThat(metadata.getValue().getContentLength()).isEqualTo(2);
        assertThat(metadata.getValue().getRawMetadata().get("x-oss-object-acl")).isEqualTo("private");
    }
    @Test void cloudErrorsNeverReachClient() {
        OSS client=mock(OSS.class);
        var factory=new DefaultListableBeanFactory();factory.registerSingleton("oss",client);
        doThrow(new IllegalStateException("test-only-secret" )).when(client).deleteObject(anyString(),anyString());
        assertThatThrownBy(()->new OssStorageClient(factory.getBeanProvider(OSS.class)).delete("bucket","key"))
                .isInstanceOf(BusinessException.class).hasMessageNotContaining("test-only-secret");
    }
}
