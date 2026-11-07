package com.ginkgo.foodmap.user;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.user.policy.UserProfilePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** 简介长度、常见联系方式与正常用餐表达的边界；不把基础正则宣称为完整反规避风控。 */
class UserProfilePolicyTest {
    @Test void countsUnicodeCodePointsAndKeepsOriginalText() {
        assertThat(UserProfilePolicy.bio("🥟".repeat(100))).isEqualTo("🥟".repeat(100));
        assertThatThrownBy(() -> UserProfilePolicy.bio("🥟".repeat(101))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> UserProfilePolicy.bio("中".repeat(101))).isInstanceOf(BusinessException.class);
        assertThat(UserProfilePolicy.bio("\u00a0爱吃１号面馆👨‍👩‍👧\r\n也喜欢QQ糖\u00a0"))
                .isEqualTo("爱吃１号面馆👨‍👩‍👧\n也喜欢QQ糖");
        assertThat(UserProfilePolicy.bio("\uFEFF简介\uFEFF")).isEqualTo("简介");
    }
    @ParameterizedTest
    @ValueSource(strings={"", "  \n\t ", "喜欢米线、QQ糖，也支持微信支付。", "9.5分的小店，Lv.2食友", "周末想吃第123456号套餐", "纯文本<script>alert(1)</script>"})
    void acceptsEmptyAndOrdinaryFoodDescriptions(String value) {
        assertThatCode(() -> UserProfilePolicy.bio(value)).doesNotThrowAnyException();
    }
    @ParameterizedTest
    @ValueSource(strings={"微信：food_123", "WeChat:food123", "wxid_abcdef12345", "QQ号：12345678", "Q Q:12 34 56 78",
            "138-0013-8000", "＋８６ １３８００１３８０００", "wx\u200b:food123", "https://example.com", "www.example.cn",
            "ｗｗｗ．ｅｘａｍｐｌｅ．ｃｎ", "example.com/path", "测试.中国", "192.168.1.2", "电话：028-12345678"})
    void rejectsCommonContactAndUrlForms(String value) {
        assertThatThrownBy(() -> UserProfilePolicy.bio(value)).isInstanceOf(BusinessException.class)
                .hasMessage("个人简介不能包含微信、QQ、手机号或网址");
    }
    @Test void rejectsUnsupportedControlAndSurrogateCharacters() {
        assertThatThrownBy(() -> UserProfilePolicy.bio("简介\u0000内容")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> UserProfilePolicy.bio("坏字符\ud800")).isInstanceOf(BusinessException.class);
    }
    @Test void neverReturnsFullShortOrMalformedEmailLocalPart() {
        assertThat(UserProfilePolicy.maskedEmail("member42@std.uestc.edu.cn")).isEqualTo("m***@std.uestc.edu.cn");
        assertThat(UserProfilePolicy.maskedEmail("ab@example.test")).isEqualTo("***@example.test");
        assertThat(UserProfilePolicy.maskedEmail("a@example.test")).isEqualTo("***@example.test");
        assertThat(UserProfilePolicy.maskedEmail(null)).isEqualTo("***");
        assertThat(UserProfilePolicy.maskedEmail("invalid-email")).isEqualTo("***");
    }
}
