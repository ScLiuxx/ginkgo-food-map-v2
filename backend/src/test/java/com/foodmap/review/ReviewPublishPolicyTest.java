package com.foodmap.review;

import static org.assertj.core.api.Assertions.*;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.review.dto.ReviewPublishRequest;
import com.foodmap.review.dto.ReviewRecommendation;
import com.foodmap.review.policy.ReviewPublishPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 验证纯规则边界与规范化，数据库幂等/并发由真实MySQL集成测试覆盖。 */
class ReviewPublishPolicyTest {
    private ReviewPublishRequest request() {
        var request = new ReviewPublishRequest();
        var scores = new ReviewPublishRequest.Scores();
        scores.setTaste(new BigDecimal("8"));
        scores.setValue(new BigDecimal("8"));
        scores.setService(new BigDecimal("8"));
        scores.setAmbience(new BigDecimal("7.5"));
        request.setScores(scores);
        request.setRecommendation(ReviewRecommendation.RECOMMENDED);
        request.setBody("正文长度足够的评价");
        request.setClientRequestId(UUID.randomUUID().toString());
        return request;
    }

    @Test
    void roundsHalfUpAndCanonicalizesDecimalsAndWhitespace() {
        var input = request();
        var first = ReviewPublishPolicy.normalize("1", 11, input);
        assertThat(first.getOverallScore()).isEqualByComparingTo("7.88");
        input.getScores().setTaste(new BigDecimal("8.000"));
        input.setBody("\u00a0正文长度足够的评价　\t");
        assertThat(ReviewPublishPolicy.normalize("1", 11, input).getRequestHash())
                .isEqualTo(first.getRequestHash());
    }

    /** 二态与旧版本摘要逐字节兼容；一般必须产生独立摘要，分数仍完全相同。 */
    @Test
    void preservesLegacyHashesAndDistinguishesNeutralWithoutChangingScores() throws Exception {
        var input = request();
        for (var attitude : ReviewRecommendation.values()) {
            input.setRecommendation(attitude);
            var row = ReviewPublishPolicy.normalize("1", 11, input);
            assertThat(row.getRecommendation()).isEqualTo(attitude);
            assertThat(row.getOverallScore()).isEqualByComparingTo("7.88");
            if (attitude != ReviewRecommendation.NEUTRAL) {
                String canonical =
                        "text-review-v1\n1\n8.0\n8.0\n8.0\n7.5\n"
                                + (attitude == ReviewRecommendation.RECOMMENDED ? "true" : "false")
                                + "\n正文长度足够的评价";
                String expected =
                        HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(
                                                        canonical.getBytes(
                                                                StandardCharsets.UTF_8)));
                assertThat(row.getRequestHash()).isEqualTo(expected);
                // 带图的旧二态请求也必须匹配升级前存储的摘要，而非仅验证新算法自身稳定。
                input.setPhotoIds(List.of("22", "11"));
                String photoCanonical = "photo-review-v2\n22,11\n" + canonical;
                String photoExpected =
                        HexFormat.of()
                                .formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(
                                                        photoCanonical.getBytes(
                                                                StandardCharsets.UTF_8)));
                assertThat(ReviewPublishPolicy.normalize("1", 11, input).getRequestHash())
                        .isEqualTo(photoExpected);
                input.setPhotoIds(List.of());
            }
        }
        input.setRecommendation(ReviewRecommendation.NEUTRAL);
        String neutral = ReviewPublishPolicy.normalize("1", 11, input).getRequestHash();
        input.setRecommendation(ReviewRecommendation.NOT_RECOMMENDED);
        assertThat(ReviewPublishPolicy.normalize("1", 11, input).getRequestHash())
                .isNotEqualTo(neutral);
        input.setRecommendation(null);
        assertThatThrownBy(() -> ReviewPublishPolicy.normalize("1", 11, input))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "01", "-1", "0", "9223372036854775808"})
    void rejectsMalformedIdentifiers(String id) {
        assertThatThrownBy(() -> ReviewPublishPolicy.normalize(id, 11, request()))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"7.1", "7.4", "-0.5", "10.5", "1E-999999"})
    void rejectsInvalidPrecisionWithoutRounding(String number) {
        var input = request();
        input.getScores().setTaste(new BigDecimal(number));
        assertThatThrownBy(() -> ReviewPublishPolicy.normalize("1", 11, input))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1-1-1-1-1", "not-a-uuid"})
    void requiresCanonicalUuid(String uuid) {
        var input = request();
        input.setClientRequestId(uuid);
        assertThatThrownBy(() -> ReviewPublishPolicy.normalize("1", 11, input))
                .isInstanceOf(BusinessException.class);
    }
}
