package com.foodmap.file.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** OSS配置；四个目录及临时有效期为新旧存储共用，沿用既有配置键以兼容运行环境。 */
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

    /** 本人头像与业务配图隔离，客户端不能指定目录或对象名。 */
    private String avatarPrefix = "avatars/";

    private Duration temporaryTtl = Duration.ofHours(24);

    /** 即使只开启COS，也必须验证共用目录及有效期，防止越出授权前缀。 */
    public boolean validImageSettings() {
        var prefixes =
                java.util.Arrays.asList(prefix, restaurantPrefix, windowPrefix, avatarPrefix);
        return prefixes.stream()
                        .allMatch(
                                value ->
                                        value != null
                                                && value.matches("[A-Za-z0-9][A-Za-z0-9/_-]*/"))
                && prefixes.stream().distinct().count() == 4
                && temporaryTtl != null
                && temporaryTtl.toSeconds() > 0;
    }
}
