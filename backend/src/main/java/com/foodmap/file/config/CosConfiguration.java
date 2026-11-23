package com.foodmap.file.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.BasicSessionCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.region.Region;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 固定官方地域Endpoint、HTTPS及有界请求；不接收前端传入的凭证或Endpoint。 */
@Configuration
@EnableConfigurationProperties(CosProperties.class)
public class CosConfiguration {
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "app.file.cos.enabled", havingValue = "true")
    public COSClient cosClient(CosProperties properties, OssProperties oss) {
        if (!properties.getRegion().matches("[a-z]{2}-[a-z0-9-]+")
                || !properties.getBucket().matches("[a-z0-9][a-z0-9-]{0,49}-[0-9]{5,20}")
                || properties.getSecretId().isBlank()
                || properties.getSecretKey().isBlank()
                || !oss.validImageSettings()
                || (oss.isEnabled() && properties.getBucket().equals(oss.getBucket()))) {
            throw new IllegalStateException("COS配置无效，请核对地域、完整桶名及凭证");
        }
        var config = new ClientConfig(new Region(properties.getRegion()));
        config.setHttpProtocol(HttpProtocol.https);
        config.setConnectionTimeout(3000);
        config.setConnectionRequestTimeout(3000);
        config.setSocketTimeout(10000);
        config.setRequestTimeout(30000);
        config.setRequestTimeOutEnable(true);
        config.setMaxErrorRetry(1);
        config.setMaxConnectionsCount(20);
        config.setRedirectsEnabled(false);
        COSCredentials credentials =
                properties.getSessionToken().isBlank()
                        ? new BasicCOSCredentials(
                                properties.getSecretId(), properties.getSecretKey())
                        : new BasicSessionCredentials(
                                properties.getSecretId(),
                                properties.getSecretKey(),
                                properties.getSessionToken());
        return new COSClient(credentials, config);
    }
}
