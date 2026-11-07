package com.ginkgo.foodmap.file.config;

import com.aliyun.oss.*;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.auth.EnvironmentVariableCredentialsProvider;
import com.aliyun.oss.common.comm.SignVersion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 单例客户端复用连接；只有显式开启时才初始化云依赖。 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OssProperties.class)
public class OssConfiguration {
    /** 强制HTTPS与V4签名；有界网络超时，避免清理或上传无限占用线程。 */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "app.file.oss.enabled", havingValue = "true")
    public OSS ossClient(OssProperties p)
            throws com.aliyun.oss.common.auth.InvalidCredentialsException {
        if (!p.getEndpoint().matches("https://oss-[a-z0-9-]+\\.aliyuncs\\.com")
                || !p.getBucket().matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]")
                || p.getRegion().isBlank()
                || !p.getPrefix().matches("[A-Za-z0-9][A-Za-z0-9/_-]*/")
                || !p.getRestaurantPrefix().matches("[A-Za-z0-9][A-Za-z0-9/_-]*/")
                || p.getPrefix().equals(p.getRestaurantPrefix())
                || p.getTemporaryTtl().toSeconds() < 1) {
            throw new IllegalStateException("OSS配置无效，请核对HTTPS Endpoint、Region、Bucket和临时有效期");
        }
        var config = new ClientBuilderConfiguration();
        config.setSignatureVersion(SignVersion.V4);
        config.setConnectionTimeout(3000);
        config.setSocketTimeout(10000);
        config.setConnectionRequestTimeout(3000);
        config.setRequestTimeout(30000);
        config.setRequestTimeoutEnabled(true);
        config.setMaxErrorRetry(1);
        // 本地显式凭证优先，两个字段必须一起填写；部分填写时失败，避免悄悄使用其他账号。
        CredentialsProvider credentials;
        boolean hasId = p.getAccessKeyId() != null && !p.getAccessKeyId().isBlank();
        boolean hasSecret = p.getAccessKeySecret() != null && !p.getAccessKeySecret().isBlank();
        if (hasId != hasSecret) {
            throw new IllegalStateException("OSS本地AccessKey ID和Secret必须同时填写");
        }
        credentials =
                hasId
                        ? new DefaultCredentialProvider(p.getAccessKeyId(), p.getAccessKeySecret())
                        : new EnvironmentVariableCredentialsProvider();
        return OSSClientBuilder.create()
                .endpoint(p.getEndpoint())
                .region(p.getRegion())
                .credentialsProvider(credentials)
                .clientConfiguration(config)
                .build();
    }
}
