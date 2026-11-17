package com.foodmap.review.policy;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.review.dto.ReviewPublishRequest;
import com.foodmap.review.dto.ReviewSubmissionRequest;
import com.foodmap.review.entity.ReviewEntity;
import com.foodmap.review.exception.ReviewErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/** 待发布点评沿用正式内容校验；独立摘要命名空间不依赖尚不存在的正式店铺ID。 */
public final class ReviewSubmissionPolicy {
    private ReviewSubmissionPolicy() {}

    /** UUID来自外层申请，作者来自认证上下文；固定字段顺序、附图顺序及末尾正文保证摘要无歧义。 */
    public static ReviewEntity normalize(long author, String uuid, ReviewSubmissionRequest input) {
        if (input == null) {
            throw new BusinessException(ReviewErrorCode.INVALID_INPUT);
        }
        var request = new ReviewPublishRequest();
        request.setClientRequestId(uuid);
        request.setScores(input.getScores());
        request.setRecommendation(input.getRecommendation());
        request.setBody(input.getBody());
        request.setPhotoIds(input.getPhotoIds());
        var row = ReviewPublishPolicy.normalizeContent(author, request);
        String canonical =
                String.join(
                        "\n",
                        "review-submission-v1",
                        row.getTasteScore().toPlainString(),
                        row.getValueScore().toPlainString(),
                        row.getServiceScore().toPlainString(),
                        row.getAmbienceScore().toPlainString(),
                        row.getRecommendation().name(),
                        ReviewPublishPolicy.photoIds(request).stream()
                                .map(String::valueOf)
                                .collect(Collectors.joining(",")),
                        row.getBody());
        try {
            row.setRequestHash(
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(canonical.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        return row;
    }

    /** 和正式点评使用同一有序图片ID规则，不在策略层查询媒体文件或操作数据库。 */
    public static List<Long> photoIds(ReviewSubmissionRequest input) {
        var request = new ReviewPublishRequest();
        request.setPhotoIds(input.getPhotoIds());
        return ReviewPublishPolicy.photoIds(request);
    }
}
