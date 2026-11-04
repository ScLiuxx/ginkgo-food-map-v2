package com.ginkgo.foodmap.restaurant.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/**
 * v002搜索正文的独立白名单，不是HTTP响应或持久化Entity，也不触发自动建索引。
 * null字段省略，未知值不补0/false；墓碑因此只序列化id、deleted、projectionVersion。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RestaurantSearchDocument {
    private Long id;
    private String name;
    private String categoryCode;
    private String categoryName;
    private String restaurantType;
    private List<String> tags;
    private String address;
    private Boolean supportsDineIn;
    private Boolean supportsDelivery;
    private Integer pricePerPerson;
    private String priceKind;
    private String businessStatus;
    private Location location;
    private Boolean needsLocation;
    private Double ratingScore;
    private Double recommendRate;
    private Long participantCount;
    private Long reviewCount;
    private Boolean deleted;
    private Long projectionVersion;
    private String coverPhotoId;

    /** 固定ES元数据_id来源，不序列化为_source字段；数字正文ID与元数据始终来自同一主键。 */
    @JsonIgnore
    public String documentId() {
        return id.toString();
    }

    /** 显式lat/lon对象保留GCJ-02原始十进制值，避免数组经纬度顺序混淆。 */
    @Data
    public static class Location {
        private BigDecimal lat;
        private BigDecimal lon;
    }
}
