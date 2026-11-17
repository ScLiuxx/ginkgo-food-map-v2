package com.foodmap.review.vo;

import com.foodmap.user.dto.PublicAuthor;
import lombok.Data;
import java.util.List;

/** 作者在同店的历史分段；当前代表ID用于前端检测读取期间的新评/删除变化。 */
@Data
public class ReviewHistoryVo {
    private PublicAuthor author;
    private String currentReviewId;
    private List<ReviewPageVo.CurrentReview> records = List.of();
    private boolean hasMore;
    private String nextCursor;
}
