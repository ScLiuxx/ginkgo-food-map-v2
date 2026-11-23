package com.foodmap.review.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;

import lombok.Data;
import lombok.ToString;

import java.util.List;

/** 新店附带的点评内容；请求UUID沿用外层提交，作者、目标和正式发布UUID均由服务端确定。 */
@Data
public class ReviewSubmissionRequest {
    private ReviewPublishRequest.Scores scores;
    private ReviewRecommendation recommendation;
    @ToString.Exclude private String body;
    private List<String> photoIds = List.of();

    /** 与正式点评一样只接受精确三态字符串，不能把布尔值或数字当作枚举序号。 */
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

    /** 拒绝嵌套身份、目标、UUID和任意图片URL，避免让调用方误以为这些字段已经保存。 */
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("待发布点评包含不支持的字段");
    }
}
