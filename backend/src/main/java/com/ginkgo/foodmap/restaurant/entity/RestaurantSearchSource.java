package com.ginkgo.foodmap.restaurant.entity;

import lombok.Data;
import java.math.BigDecimal;

/** 搜索组装专用的数据库读取模型；只含本模块业务字段与原始统计，不加载账号、审核快照或审计时间。 */
@Data
public class RestaurantSearchSource {
    private Long id;
    /** 与业务来源在同一RR快照读取，禁止调用方自行指定或使用时间戳替代。 */
    private long projectionVersion;
    private String name;
    private String categoryCode;
    private String categoryName;
    private String restaurantType;
    private Boolean supportsDineIn;
    private Boolean supportsDelivery;
    private Integer pricePerPerson;
    private String priceKind;
    private String address;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private boolean needsLocation;
    private String businessStatus;
    private boolean deleted;
    private long reviewCount;
    private long participantCount;
    private long recommendCount;
    private BigDecimal overallScoreSum;
}
