package com.ginkgo.foodmap.review;

import static org.assertj.core.api.Assertions.*;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.review.dto.ReviewPublishRequest;
import com.ginkgo.foodmap.review.policy.ReviewPublishPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
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
        request.setRecommended(true);
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
