package com.foodmap.file.config;

import lombok.Data;
import lombok.ToString;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** COS开启后接收新上传；旧桶记录仍按桶名路由，不自动复制或改写媒体记录。 */
@Data
@ConfigurationProperties("app.file.cos")
public class CosProperties {
    private boolean enabled;
    private String region = "";
    private String bucket = "";
    @ToString.Exclude
    private String secretId = "";
    @ToString.Exclude
    private String secretKey = "";
    @ToString.Exclude
    private String sessionToken = "";
}
