package com.ginkgo.foodmap.review.policy;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.review.dto.ReviewHistoryBoundary;
import com.ginkgo.foodmap.review.exception.ReviewErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** 版本化的公开游标；不作为权限凭证，拒绝跨餐厅/作者使用和非规范编码。 */
public final class ReviewHistoryCursorPolicy {
    private ReviewHistoryCursorPolicy() {}

    /** 携带微秒精度时间和ID，等时间点评仍能无重复地向后翻页。 */
    public static String encode(long restaurant, long author, Instant time, long id) {
        String raw =
                "1:"
                        + restaurant
                        + ":"
                        + author
                        + ":"
                        + time.getEpochSecond()
                        + ":"
                        + time.getNano()
                        + ":"
                        + id;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 解码只产生类型化参数；服务还会核对边界记录的真实归属和时间，绝不拼接SQL。 */
    public static ReviewHistoryBoundary decode(String value, long restaurant, long author) {
        try {
            if (value == null || !value.matches("[A-Za-z0-9_-]{1,200}")) {
                throw new IllegalArgumentException();
            }
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] fields = raw.split(":", -1);
            if (fields.length != 6
                    || !fields[0].equals("1")
                    || !fields[1].equals(Long.toString(restaurant))
                    || !fields[2].equals(Long.toString(author))) {
                throw new IllegalArgumentException();
            }
            long seconds = Long.parseLong(fields[3]);
            int nanos = Integer.parseInt(fields[4]);
            long id = Long.parseLong(fields[5]);
            if (seconds < 0 || nanos < 0 || nanos > 999999999 || nanos % 1000 != 0 || id <= 0) {
                throw new IllegalArgumentException();
            }
            var time = Instant.ofEpochSecond(seconds, nanos);
            if (!encode(restaurant, author, time, id).equals(value)) {
                throw new IllegalArgumentException();
            }
            var boundary = new ReviewHistoryBoundary();
            boundary.setId(id);
            boundary.setCreatedAt(time);
            return boundary;
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            throw new BusinessException(ReviewErrorCode.INVALID_CURSOR);
        }
    }
}
