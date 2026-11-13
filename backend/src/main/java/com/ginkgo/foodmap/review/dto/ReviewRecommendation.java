package com.ginkgo.foodmap.review.dto;

/** 点评独立态度，与四维评分、店铺排序和榜单无关；编码与待发布点评表一致。 */
public enum ReviewRecommendation {
    RECOMMENDED,
    NEUTRAL,
    NOT_RECOMMENDED;

    /** 历史二态沿用原布尔文本，保证升级前发布成功的同内容重试仍匹配旧摘要。 */
    public String requestToken() {
        return switch (this) {
            case RECOMMENDED -> "true";
            case NEUTRAL -> "neutral";
            case NOT_RECOMMENDED -> "false";
        };
    }
}
