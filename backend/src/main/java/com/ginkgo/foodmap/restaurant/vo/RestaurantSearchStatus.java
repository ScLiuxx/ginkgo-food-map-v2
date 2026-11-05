package com.ginkgo.foodmap.restaurant.vo;

import lombok.Data;

/** 公开实际查询能力，不暴露ES地址或异常；降级时告知匹配与排序已使用基础规则。 */
@Data
public class RestaurantSearchStatus {
    private String engine;
    private boolean degraded;
    private String reason;
    private String effectiveSort;
    /** 关键词结果最多允许访问前10000条；总命中数仍完整返回。 */
    private Integer resultWindow;
}
