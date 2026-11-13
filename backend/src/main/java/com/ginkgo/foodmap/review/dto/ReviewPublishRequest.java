package com.ginkgo.foodmap.review.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;

import lombok.Data;
import lombok.ToString;

import java.math.BigDecimal;

/** 点评输入只接受正文、评分和已上传文件ID，作者、URL、时间与总分不得由客户端指定。 */
@Data
public class ReviewPublishRequest {
    private String clientRequestId;
    private Scores scores;
    private ReviewRecommendation recommendation;
    @ToString.Exclude private String body;

    /** 只接受已上传文件ID，按数组顺序绑定；不接受URL、Bucket和作者。 */
    private java.util.List<String> photoIds = java.util.List.of();

    /** 只接受明确的三态字符串；拒绝布尔值、数字序号、大小写别名及空白修正。 */
    @JsonSetter("recommendation")
    public void readRecommendation(Object value) {
        if (value == null) {
            recommendation = null;
            return;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("推荐态度必须是三态字符串");
        }
        recommendation = ReviewRecommendation.valueOf(text);
    }

    /** 未知字段明确拒绝，不把任意照片URL或错误参数静默忽略。 */
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("评价请求包含不支持的字段");
    }

    /** 四维先按十进制原值接收，再做半分校验，不把非法精度先舍入。 */
    @Data
    public static class Scores {
        private BigDecimal taste;
        private BigDecimal value;
        private BigDecimal service;
        private BigDecimal ambience;

        /** 错拼维度及额外分数字段必须明确拒绝。 */
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) {
            throw new IllegalArgumentException("评分包含不支持的字段");
        }
    }
}
