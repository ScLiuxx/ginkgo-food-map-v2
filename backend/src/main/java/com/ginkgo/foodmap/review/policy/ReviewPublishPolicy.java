package com.ginkgo.foodmap.review.policy;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.review.dto.ReviewPublishRequest;
import com.ginkgo.foodmap.review.entity.ReviewEntity;
import com.ginkgo.foodmap.review.exception.ReviewErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 发布规范化与稳定摘要；十进制精度、Unicode边界和幂等内容共用一套规则。 */
public final class ReviewPublishPolicy {
    /** 与前端及数据库位置0至4的约束一致，每条点评最多五张。 */
    public static final int MAX_PHOTOS = 5;

    private ReviewPublishPolicy() {}

    /** 作者来自安全上下文；入参原对象不修改，原文不会进入日志。 */
    public static ReviewEntity normalize(
            String restaurant, long author, ReviewPublishRequest request) {
        var row = new ReviewEntity();
        try {
            if (restaurant == null || !restaurant.matches("[1-9][0-9]{0,18}")) {
                throw new IllegalArgumentException();
            }
            row.setRestaurantId(Long.parseLong(restaurant));
            if (request == null
                    || request.getScores() == null
                    || request.getRecommended() == null) {
                throw new IllegalArgumentException();
            }
            String uuid = request.getClientRequestId();
            if (uuid == null || !UUID.fromString(uuid).toString().equalsIgnoreCase(uuid)) {
                throw new IllegalArgumentException();
            }
            row.setClientRequestId(uuid.toLowerCase(java.util.Locale.ROOT));
            var scores = request.getScores();
            row.setTasteScore(score(scores.getTaste()));
            row.setValueScore(score(scores.getValue()));
            row.setServiceScore(score(scores.getService()));
            row.setAmbienceScore(score(scores.getAmbience()));
            String body = trim(request.getBody());
            if (body.codePointCount(0, body.length()) < 5
                    || body.codePointCount(0, body.length()) > 500) {
                throw new IllegalArgumentException();
            }
            row.setBody(body);
            row.setAuthorUserId(author);
            row.setRecommended(request.getRecommended());
            row.setOverallScore(
                    row.getTasteScore()
                            .add(row.getValueScore())
                            .add(row.getServiceScore())
                            .add(row.getAmbienceScore())
                            .divide(BigDecimal.valueOf(4), 2, RoundingMode.HALF_UP));
            // 固定版本、固定字段顺序，正文在最后，避免分隔符出现在正文时产生歧义。
            String canonical =
                    String.join(
                            "\n",
                            List.of(
                                    "text-review-v1",
                                    Long.toString(row.getRestaurantId()),
                                    row.getTasteScore().toPlainString(),
                                    row.getValueScore().toPlainString(),
                                    row.getServiceScore().toPlainString(),
                                    row.getAmbienceScore().toPlainString(),
                                    Boolean.toString(row.isRecommended()),
                                    body));
            var photos = photoIds(request);
            // 空图保持既有text-review-v1逐字节兼容；附图版本将有序ID纳入摘要。
            if (!photos.isEmpty()) {
                canonical =
                        "photo-review-v2\n"
                                + photos.stream()
                                        .map(String::valueOf)
                                        .collect(java.util.stream.Collectors.joining(","))
                                + "\n"
                                + canonical;
            }
            row.setRequestHash(
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(canonical.getBytes(StandardCharsets.UTF_8))));
            return row;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ReviewErrorCode.INVALID_INPUT);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** 非空列表最多五张，拒绝重复、负数、溢出和非规范ID；不修改调用方对象。 */
    public static List<Long> photoIds(ReviewPublishRequest request) {
        try {
            if (request == null
                    || request.getPhotoIds() == null
                    || request.getPhotoIds().size() > MAX_PHOTOS) {
                throw new IllegalArgumentException();
            }
            var result =
                    request.getPhotoIds().stream()
                            .map(
                                    id -> {
                                        if (id == null || !id.matches("[1-9][0-9]{0,18}")) {
                                            throw new IllegalArgumentException();
                                        }
                                        return Long.parseLong(id);
                                    })
                            .toList();
            if (result.stream().distinct().count() != result.size()) {
                throw new IllegalArgumentException();
            }
            return result;
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ReviewErrorCode.INVALID_INPUT);
        }
    }

    private static BigDecimal score(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.TEN) > 0) {
            throw new IllegalArgumentException();
        }
        value = value.stripTrailingZeros();
        // 先排除超出一位的有效小数，避免极端指数输入在remainder中制造巨大十进制运算。
        if (value.scale() > 1 || value.remainder(new BigDecimal("0.5")).signum() != 0) {
            throw new IllegalArgumentException();
        }
        return value.setScale(1, RoundingMode.UNNECESSARY);
    }

    /** 与餐厅规范化一致，额外涵盖Java strip未移除的不换行空格。 */
    private static String trim(String value) {
        if (value == null) {
            throw new IllegalArgumentException();
        }
        int start = 0, end = value.length();
        while (start < end && space(value.codePointAt(start))) {
            start += Character.charCount(value.codePointAt(start));
        }
        while (end > start && space(value.codePointBefore(end))) {
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.substring(start, end);
    }

    private static boolean space(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }
}
