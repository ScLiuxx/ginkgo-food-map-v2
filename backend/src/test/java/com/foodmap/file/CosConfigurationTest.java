package com.foodmap.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.foodmap.file.config.CosConfiguration;
import com.foodmap.file.config.CosProperties;
import com.foodmap.file.config.OssConfiguration;
import com.foodmap.file.config.OssProperties;
import com.foodmap.file.job.MediaCleanupJob;
import com.foodmap.file.service.MediaCleanupService;
import com.qcloud.cos.COSClient;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 配置只构建客户端，不发送云请求；仅COS开启时也必须建立回收任务。 */
class CosConfigurationTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner()
                    .withUserConfiguration(CosConfiguration.class, OssConfiguration.class)
                    .withPropertyValues(
                            "app.file.cos.enabled=true",
                            "app.file.cos.region=ap-guangzhou",
                            "app.file.cos.bucket=fixture-images-1234567890",
                            "app.file.cos.secret-id=fixture-only-id",
                            "app.file.cos.secret-key=fixture-only-secret");

    @Test
    void credentialsBindWithoutLeakingAndCosOnlyStartsCleanupJob() {
        context.withUserConfiguration(MediaCleanupJob.class)
                .withBean(
                        MediaCleanupService.class,
                        () -> org.mockito.Mockito.mock(MediaCleanupService.class))
                .run(
                        c -> {
                            assertThat(c).hasNotFailed().hasSingleBean(COSClient.class);
                            assertThat(c).hasSingleBean(MediaCleanupJob.class);
                            assertThat(c.getBean(CosProperties.class).toString())
                                    .doesNotContain("fixture-only-id", "fixture-only-secret");
                        });
    }

    @Test
    void partialCredentialsOrInvalidSharedPrefixesFailBeforeCloudRequests() {
        var p = new CosProperties();
        p.setRegion("ap-guangzhou");
        p.setBucket("fixture-images-1234567890");
        p.setSecretId("fixture-only-id");
        assertThatThrownBy(() -> new CosConfiguration().cosClient(p, new OssProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("fixture-only-id");
        p.setSecretKey("fixture-only-secret");
        var common = new OssProperties();
        common.setAvatarPrefix("../avatars/");
        assertThatThrownBy(() -> new CosConfiguration().cosClient(p, common))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noCloudStorageKeepsCleanupJobDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(MediaCleanupJob.class)
                .run(c -> assertThat(c).hasNotFailed().doesNotHaveBean(MediaCleanupJob.class));
    }
}
