package com.foodmap.review.vo;

import com.foodmap.review.dto.ReviewRecommendation;

import lombok.Data;
import lombok.ToString;

import java.util.List;

/** 仅供作者查看的附带点评白名单；不包含文件ID、摘要或内部发布幂等凭据。 */
@Data
public class ReviewSubmissionVo {
    private String id;
    private String status;
    private ReviewPageVo.Scores scores;
    private ReviewRecommendation recommendation;
    @ToString.Exclude
    private String body;
    private List<Integer> photoPositions;

    /** 已发布时只返回回执，内容由正式点评入口管理，避免恢复已清空的私有草稿。 */
    private String publishedReviewId;
}
