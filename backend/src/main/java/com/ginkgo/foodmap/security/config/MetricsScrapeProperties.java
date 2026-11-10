package com.ginkgo.foodmap.security.config;

import lombok.Data;
import lombok.ToString;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 独立采集凭据只用于指标端点；生产通过环境变量注入，禁止打印配置中的密码。 */
@Data
@ConfigurationProperties("app.security.metrics")
public class MetricsScrapeProperties {
    private boolean enabled;
    @ToString.Exclude private String password;
}
