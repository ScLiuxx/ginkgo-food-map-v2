package com.ginkgo.foodmap.restaurant.vo;

import lombok.Data;

import java.util.List;

/** 总数与记录使用同一筛选快照；空集合总页数为0，越界页不静默跳页。 */
@Data
public class RestaurantPageVo {
    private RestaurantSearchStatus search;
    /** 可访问页数与真实总页数分开，避免把结果窗口误报为总数。 */
    private long accessiblePages;
    private List<RestaurantVo> records;
    private long total;
    private int page;
    private int pageSize;
    private long totalPages;
}
