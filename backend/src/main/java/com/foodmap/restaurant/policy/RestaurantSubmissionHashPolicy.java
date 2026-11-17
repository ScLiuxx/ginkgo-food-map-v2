package com.foodmap.restaurant.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** 店铺申请摘要的共同边界；无点评逐字节兼容旧协议，点评独立保存且仅摘要参与联合内容。 */
public final class RestaurantSubmissionHashPolicy {
    private RestaurantSubmissionHashPolicy() {}

    /** reviewHash为空表示请求未提供点评；重提的省略语义为沿用，重放不能再次覆盖后来编辑的点评。 */
    public static byte[] hash(String payload, String reviewHash) {
        // MySQL HEX读取为大写，规范化器输出小写；统一字母大小写保证重复保存的联合摘要稳定。
        String canonical =
                reviewHash == null
                        ? payload
                        : "restaurant-review-submission-v1\n"
                                + payload
                                + "\n"
                                + reviewHash.toLowerCase(Locale.ROOT);
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
