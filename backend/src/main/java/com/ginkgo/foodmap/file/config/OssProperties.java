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
    @lombok.ToString.Exclude private String accessKeyId = "";

    @lombok.ToString.Exclude private String accessKeySecret = "";

    /** 餐厅和窗口点评照片共用此目录，具体归属由数据库关联，禁止客户端指定路径。 */
    private String prefix = "review-images/";

    /** 同级商家图片目录：投稿、纠错和正式相册共用对象，审核通过后不移动文件。 */
    private String restaurantPrefix = "restaurant-images/";

    /** 管理员窗口相册独立于窗口点评；与其他用途使用同级目录。 */
    private String windowPrefix = "windows-images/";

    private Duration temporaryTtl = Duration.ofHours(24);
}
