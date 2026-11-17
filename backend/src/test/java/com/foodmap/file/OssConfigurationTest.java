package com.foodmap.file;

import com.aliyun.oss.OSS;
import com.foodmap.file.config.OssConfiguration;
import com.foodmap.file.config.OssProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

/** 不连接OSS，验证本地配置绑定、显式凭证构建、部分填写拒绝及日志脱敏。 */
class OssConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(OssConfiguration.class)
        .withPropertyValues("app.file.oss.enabled=true", "app.file.oss.endpoint=https://oss-cn-chengdu.aliyuncs.com",
            "app.file.oss.region=cn-chengdu", "app.file.oss.bucket=test-config-bucket");

    @Test void bindsLocalCredentialsAndBuildsClientWithoutCloudRequests() {
        context.withPropertyValues("app.file.oss.access-key-id=test-only-id", "app.file.oss.access-key-secret=test-only-secret")
            .run(c->{
                assertThat(c).hasNotFailed().hasSingleBean(OSS.class);
                var p=c.getBean(OssProperties.class);
                assertThat(p.getAccessKeyId()).isEqualTo("test-only-id");
                assertThat(p.getAccessKeySecret()).isEqualTo("test-only-secret");
                assertThat(p.toString()).doesNotContain("test-only-id", "test-only-secret");
            });
    }
    @Test void partialCredentialsFailWithoutLeakingValues() {
        var p=new OssProperties();p.setEndpoint("https://oss-cn-chengdu.aliyuncs.com");
        p.setRegion("cn-chengdu");p.setBucket("test-config-bucket");
        p.setAccessKeyId("test-only-id");
        assertThatThrownBy(()->new OssConfiguration().ossClient(p)).isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("test-only-id");
        p.setAccessKeyId("");p.setAccessKeySecret("test-only-secret");
        assertThatThrownBy(()->new OssConfiguration().ossClient(p)).isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("test-only-secret");
    }
}
