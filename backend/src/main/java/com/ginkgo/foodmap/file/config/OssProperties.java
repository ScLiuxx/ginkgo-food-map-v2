package com.ginkgo.foodmap.file.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/** OSS位置、生命周期与可选本地凭证；敏感字段必须排除toString，禁止日志输出完整配置。 */
@Data
@ConfigurationProperties("app.file.oss")
public class OssProperties {
    /** 未配置OSS时保持已有文本功能可用，上传明确返回503。 */
    private boolean enabled;
    private String endpoint = "";
    private String region = "";
    private String bucket = "";
    /** 可填写在被Git忽略的application-local.yml；未填写时沿用SDK环境凭证。 */
    @lombok.ToString.Exclude
    private String accessKeyId = "";
    @lombok.ToString.Exclude
    private String accessKeySecret = "";
    /** 后端生成的对象统一位于独立目录，禁止接受客户端传入路径。 */
    private String prefix = "review-images/";
    /** 共用Bucket中的商户私有图片目录，与点评上传用途分开。 */
    private String restaurantPrefix = "restaurant-images/";
    private Duration temporaryTtl = Duration.ofHours(24);
}
