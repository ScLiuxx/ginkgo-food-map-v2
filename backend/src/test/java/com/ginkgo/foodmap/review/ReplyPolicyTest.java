package com.ginkgo.foodmap.review;

import static org.assertj.core.api.Assertions.*;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.review.dto.ReplyPublishRequest;
import com.ginkgo.foodmap.review.policy.ReplyPolicy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

/** 覆盖Unicode边界和游标归属，补充数据库接口测试。 */
class ReplyPolicyTest {
    private ReplyPublishRequest input(String body) {
        var input = new ReplyPublishRequest();
        input.setBody(body);
        input.setClientRequestId(UUID.randomUUID().toString());
        return input;
    }

    @Test
    void normalizesUnicodeWhitespaceAndCountsCodePoints() {
        var input = input("　\u00a0🥟\u00a0 ");
        var a = ReplyPolicy.normalize("1", 11, input);
        assertThat(a.getBody()).isEqualTo("🥟");
        input.setBody("🥟");
        assertThat(ReplyPolicy.normalize("1", 11, input).getRequestHash())
                .isEqualTo(a.getRequestHash());
        assertThat(ReplyPolicy.normalize("2", 11, input).getRequestHash())
                .isNotEqualTo(a.getRequestHash());
        assertThat(ReplyPolicy.normalize("1", 11, input("🥟".repeat(200))).getBody()).hasSize(400);
        for (String body : new String[] {"", "　\u00a0", "🥟".repeat(201)}) {
            assertThatThrownBy(() -> ReplyPolicy.normalize("1", 11, input(body)))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void rejectsInvalidIdentityAndUuid() {
        for (String id : new String[] {"0", "01", "-1", "9223372036854775808", "x"}) {
            assertThatThrownBy(() -> ReplyPolicy.normalize(id, 11, input("回复")))
                    .isInstanceOf(BusinessException.class);
        }
        var input = input("回复");
        input.setClientRequestId("1-1-1-1-1");
        assertThatThrownBy(() -> ReplyPolicy.normalize("1", 11, input))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ReplyPolicy.normalize("1", 11, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void cursorPreservesMicrosecondsAndRejectsOtherReviewAndNoncanonicalEncoding() {
        var row = ReplyPolicy.normalize("1", 11, input("回复"));
        row.setId(12L);
        row.setCreatedAt(Instant.parse("2026-10-30T01:02:03.123456Z"));
        String cursor = ReplyPolicy.encode(row);
        assertThat(ReplyPolicy.decode(cursor, 1).getCreatedAt()).isEqualTo(row.getCreatedAt());
        assertThat(ReplyPolicy.decode(cursor, 1).getId()).isEqualTo(12);
        for (String invalid : new String[] {cursor + "=", "", "!", "MToxOjA6MTox"}) {
            assertThatThrownBy(() -> ReplyPolicy.decode(invalid, 1))
                    .isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> ReplyPolicy.decode(cursor, 2))
                .isInstanceOf(BusinessException.class);
    }
}
