package com.ginkgo.foodmap.restaurant.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 独立选店条件；不限必须显式传ANY，不能把缺字段解释为用户已经选择。 */
@Data
public class RestaurantPickerRequest {
    @NotNull
    @Pattern(regexp = "QINGSHUIHE|SHAHE|USER")
    private String range;

    @NotNull
    private Integer distanceMeters;

    @NotNull
    @Pattern(regexp = "ANY|15|30|50|100")
    private String budget;

    /** 五分制最低综合口碑分；不限也必须主动选择，不能用缺字段放宽候选集合。 */
    @NotNull
    @Pattern(regexp = "ANY|3|3\\.5|4")
    private String minRating;

    @NotNull
    @Pattern(regexp = "[A-Z][A-Z0-9_]{0,31}")
    private String category;

    @NotNull
    @Pattern(regexp = "ANY|DINE_IN|DELIVERY")
    private String diningMode;

    @DecimalMin("-90")
    @DecimalMax("90")
    private BigDecimal latitude;

    @DecimalMin("-180")
    @DecimalMax("180")
    private BigDecimal longitude;

    /** 已展示与已预取ID均排除；设置请求体规模上限，不拼接SQL或传递分页参数。 */
    @NotNull
    @Size(max = 50000)
    private List<@NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String> excludeIds = List.of();

    /** 非空时只复核这批预取/历史店铺，按请求顺序返回仍符合条件项，不消耗新候选。 */
    @NotNull
    @Size(max = 5)
    private List<@NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String> checkIds = List.of();

    /** 与已确认的三个档位保持一致，不把缺失距离视为默认值或不限。 */
    @AssertTrue(message = "距离只能选择1、3或5公里")
    public boolean isDistanceValid() {
        return distanceMeters != null && List.of(1000, 3000, 5000).contains(distanceMeters);
    }

    /** 校区中心由服务端解析；我附近必须显式提供成对的GCJ-02坐标，缺失不能变为不限范围。 */
    @AssertTrue(message = "我附近需要有效位置，校区范围不接受自定义中心")
    public boolean isLocationValid() {
        return "USER".equals(range)
                ? latitude != null && longitude != null
                : latitude == null && longitude == null;
    }
}
