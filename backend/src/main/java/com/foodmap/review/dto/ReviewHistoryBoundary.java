package com.foodmap.review.dto;

import lombok.Data;
import java.time.Instant;

/** 解码后的排他边界；餐厅/作者归属校验后才允许作为绑定参数参与SQL。 */
@Data
public class ReviewHistoryBoundary {
    private long id;
    private Instant createdAt;
}
