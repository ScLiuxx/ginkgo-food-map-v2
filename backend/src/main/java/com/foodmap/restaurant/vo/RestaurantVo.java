package com.foodmap.restaurant.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 列表与详情共用的公开餐厅字段白名单，不携带账号ID、删除元数据或行版本。
 * ID使用字符串避免浏览器整数精度损失；无人计分时分数为null，不能补成0。
 * 推荐态度只在单条点评展示，不作为商户聚合字段返回。
 */
@Data
public class RestaurantVo {
    private String id;
    private String name;
    private String categoryCode;
    private String restaurantType;
    private Boolean supportsDineIn;
    private Boolean supportsDelivery;
    /** 随目录批量返回正式相册首图；无已发布封面为null，不用点评图片补造。 */
    private RestaurantPhotoViews.Item coverPhoto;

    private String categoryName;
    private Integer pricePerPerson;
    private String priceKind;
    private String address;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private boolean needsLocation;
    private String businessStatus;
    private Instant createdAt;
    private Instant updatedAt;
    private Double distanceKm;
    private List<String> tags;
    /** 商户聚合综合分，0–10，四舍五入一位小数；原始累计统计不变。 */
    private BigDecimal score;
    /** 五分制综合口碑分，展示和综合排序共用；无人计分为null，原score保留十分制均分语义。 */
    private BigDecimal reputationScore;
    private long reviewCount;
    /** 有效计分账号数；同人历史点评不增加该人数，也不重复贡献分数。 */
    private long participantCount;
    private Dimensions dimensions = new Dimensions();

    /** 四维均分的空值是尚无评价，不可转换为零分。 */
    @Data
    public static class Dimensions {
        private BigDecimal taste;
        private BigDecimal value;
        private BigDecimal service;
        private BigDecimal ambience;
    }
}
