package com.ginkgo.foodmap.review.vo;

import com.ginkgo.foodmap.user.dto.PublicAuthor;
import lombok.Data;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 公开的作者组分页；人数和条数分别表示，历史另行按需加载，不内嵌。 */
@Data
public class ReviewPageVo {
    private List<Group> records = List.of();
    private long totalGroups;
    private long totalReviews;
    private int page;
    private int pageSize;
    private long totalPages;

    /** 每位作者一张主卡；有历史时返回当前代表的排他游标，用于首次展开。 */
    @Data
    public static class Group {
        private PublicAuthor author;
        private CurrentReview currentReview;
        private long historyCount;
        private String nextHistoryCursor;
    }

    /** 展示字段显式列出，不包含发布幂等材料或管理权限。 */
    @Data
    public static class CurrentReview {
        private String id;
        private Scores scores;
        private BigDecimal overallScore;
        private boolean recommended;
        private String body;
        private Instant createdAt;
        private String scoringStatus = "current";
        private List<Photo> photos = List.of();
    }

    /** 四维保持数据库十进制分值，不在客户端重算权威综合分。 */
    @Data
    public static class Scores {
        private BigDecimal taste;
        private BigDecimal value;
        private BigDecimal service;
        private BigDecimal ambience;
    }

    /** 相对URL复用现有正式照片接口，不签发OSS地址，也不下载图片字节。 */
    @Data
    public static class Photo {
        private String id;
        private int position;
        private String url;
    }
}
