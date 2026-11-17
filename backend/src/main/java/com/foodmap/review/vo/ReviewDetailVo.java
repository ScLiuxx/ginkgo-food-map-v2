package com.foodmap.review.vo;

import com.foodmap.user.dto.PublicAuthor;

import lombok.Data;

/** 单条正式点评白名单；保留真实店铺类型和历史身份，不输出投稿草稿或管理权限。 */
@Data
public class ReviewDetailVo {
    private String restaurantId;
    private String windowId;
    private PublicAuthor author;
    private ReviewPageVo.CurrentReview review;
}
