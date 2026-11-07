package com.ginkgo.foodmap.review.policy;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.review.dto.ReplyPublishRequest;
import com.ginkgo.foodmap.review.entity.ReplyEntity;
import com.ginkgo.foodmap.review.exception.ReplyErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/** 回复规范化及公开分页边界；游标仅作边界，不能作为授权凭证。 */
public final class ReplyPolicy {
    private ReplyPolicy() {}

    /** 路径ID拒绝前导零、溢出及非正数。 */
    public static long reviewId(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
                throw new IllegalArgumentException();
            }
            return Long.parseLong(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ReplyErrorCode.INVALID_INPUT);
        }
    }

    /** Unicode空白归一后按码点计数；摘要包含点评ID，跨点评复用编号也会冲突。 */
    public static ReplyEntity normalize(String review, long author, ReplyPublishRequest input) {
        long id = reviewId(review);
        try {
            if (input == null || input.getBody() == null || input.getClientRequestId() == null) {
                throw new IllegalArgumentException();
            }
            String uuid = input.getClientRequestId();
            if (!UUID.fromString(uuid).toString().equalsIgnoreCase(uuid)) {
                throw new IllegalArgumentException();
            }
            String text = input.getBody();
            int start = 0, end = text.length();
            while (start < end && space(text.codePointAt(start))) {
                start += Character.charCount(text.codePointAt(start));
            }
            while (end > start && space(text.codePointBefore(end))) {
                end -= Character.charCount(text.codePointBefore(end));
            }
            text = text.substring(start, end);
            int length = text.codePointCount(0, text.length());
            if (length < 1 || length > 200) {
                throw new IllegalArgumentException();
            }
            var row = new ReplyEntity();
            row.setReviewId(id);
            row.setAuthorUserId(author);
            row.setBody(text);
            row.setClientRequestId(uuid.toLowerCase(Locale.ROOT));
            row.setRequestHash(
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    ("reply-v1\n" + id + "\n" + text)
                                                            .getBytes(StandardCharsets.UTF_8))));
            return row;
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ReplyErrorCode.INVALID_INPUT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean space(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    /** 微秒时间加ID稳定排序，同时间回复也可逐条翻页。 */
    public static String encode(ReplyEntity row) {
        String raw =
                "1:"
                        + row.getReviewId()
                        + ":"
                        + row.getCreatedAt().getEpochSecond()
                        + ":"
                        + row.getCreatedAt().getNano()
                        + ":"
                        + row.getId();
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 严格校验版本、归属及规范编码，服务还会校验锚点记录。 */
    public static ReplyEntity decode(String value, long review) {
        try {
            if (value == null || !value.matches("[A-Za-z0-9_-]{1,200}")) {
                throw new IllegalArgumentException();
            }
            var parts =
                    new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
                            .split(":", -1);
            if (parts.length != 5
                    || !parts[0].equals("1")
                    || !parts[1].equals(Long.toString(review))) {
                throw new IllegalArgumentException();
            }
            long seconds = Long.parseLong(parts[2]), id = Long.parseLong(parts[4]);
            int nanos = Integer.parseInt(parts[3]);
            if (seconds < 0 || id <= 0 || nanos < 0 || nanos > 999999999 || nanos % 1000 != 0) {
                throw new IllegalArgumentException();
            }
            var row = new ReplyEntity();
            row.setReviewId(review);
            row.setId(id);
            row.setCreatedAt(Instant.ofEpochSecond(seconds, nanos));
            if (!encode(row).equals(value)) {
                throw new IllegalArgumentException();
            }
            return row;
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            throw new BusinessException(ReplyErrorCode.INVALID_CURSOR);
        }
    }
}
