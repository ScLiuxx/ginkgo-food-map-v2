package com.ginkgo.foodmap.restaurant.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import com.ginkgo.foodmap.restaurant.policy.RestaurantSearchQueryPolicy;

import java.math.BigDecimal;

/**
 * 公开目录的查询参数，自动选择默认意图，显式业务排序不被相关度覆盖。
 *
 * <p>分页在数据库执行；每页上限用于限制单次读取成本。派生属性没有 Setter，不能由 HTTP
 * 输入覆盖 SQL 条件；LIKE 使用 ! 转义，用户输入的百分号、下划线和反斜杠都按字面量搜索。</p>
 */
@Data
public class RestaurantListQuery {
    @NotNull
    @Size(max = 100, message = "搜索词最多100个字符")
    private String keyword = "";

    @NotNull
    @Pattern(regexp = "(?:[A-Z][A-Z0-9_]{0,31})?", message = "分类编码格式错误")
    private String category = "";

    /** 类型独立于美食分类，空值不限；食堂不依赖旧CANTEEN分类编码识别。 */
    @NotNull
    @Pattern(regexp = "(?:REGULAR|CANTEEN)?", message = "餐厅类型错误")
    private String restaurantType = "";

    /** 用餐方式只匹配明确支持，未知及false均不满足；空值不限。 */
    @NotNull
    @Pattern(regexp = "(?:dineIn|delivery)?", message = "用餐方式错误")
    private String diningMode = "";

    /** 用户单次授权取得的GCJ-02位置，仅用于本次查询，不写入数据库。 */
    @DecimalMin("-90") @DecimalMax("90")
    private BigDecimal latitude;
    @DecimalMin("-180") @DecimalMax("180")
    private BigDecimal longitude;

    @AssertTrue(message = "用户经纬度必须同时提供")
    public boolean isUserLocationValid() {
        return (latitude == null) == (longitude == null);
    }

    @NotNull
    @Pattern(regexp = "auto|relevance|score|reviews|price|distance|recommend", message = "排序方式错误")
    private String sort = "auto";

    /** 仅内部引擎编排设置；HTTP白名单不允许绑定，用于受支持搜索的并列ID次序。 */
    private boolean searchOrderById;

    /** 单字及普通目录默认综合分，多字默认相关度；显式排序不受默认意图影响。 */
    public String getEffectiveSort() {
        return "auto".equals(sort)
                ? (keyword.strip().isEmpty() || RestaurantSearchQueryPolicy.isSingleHan(keyword) ? "score" : "relevance")
                : sort;
    }

    @NotNull
    private Boolean hideClosed = false;

    /** 人均上限含边界，空值表示不限；未知价格由SQL排除，精度沿用金额字段。 */
    @DecimalMin(value = "0.01", message = "人均上限必须大于0")
    @Digits(integer = 8, fraction = 2, message = "人均上限最多8位整数和2位小数")
    private BigDecimal maxPrice;

    /** 距当前请求用户位置的球面直线距离上限，单位米；空值不限。 */
    @Min(value = 1, message = "距离上限至少1米")
    @Max(value = 20040000, message = "距离上限超出球面距离范围")
    private Integer maxDistanceMeters;

    @NotNull
    @Min(value = 1, message = "页码必须大于0")
    private Integer page = 1;

    @NotNull
    @Min(value = 1, message = "每页至少1条")
    @Max(value = 100, message = "每页最多100条")
    private Integer pageSize = 8;

    /** 可选GCJ-02视口；四边必须同时提供，普通目录不传时仍查询完整集合。 */
    @DecimalMin("-180") @DecimalMax("180")
    private BigDecimal west;
    @DecimalMin("-180") @DecimalMax("180")
    private BigDecimal east;
    @DecimalMin("-90") @DecimalMax("90")
    private BigDecimal south;
    @DecimalMin("-90") @DecimalMax("90")
    private BigDecimal north;

    /** 首版不支持跨日期变更线，拒绝缺边、反向或零面积，不能静默放宽查询。 */
    @AssertTrue(message = "地图范围必须完整且西小于东、南小于北")
    public boolean isBoundsValid() {
        if (west == null && east == null && south == null && north == null) return true;
        return west != null && east != null && south != null && north != null
                && west.compareTo(east) < 0 && south.compareTo(north) < 0;
    }

    /** 先转换为 long 再乘，较大但合法的页码不能发生 int 溢出后读取错误页。 */
    public long getOffset() {
        return ((long) page - 1) * pageSize;
    }

    /** 参数绑定与 LIKE 转义分别防止 SQL 注入和通配符扩大搜索，不拼接用户 SQL。 */
    public String getKeywordPattern() {
        return "%" + keyword.strip().replace("!", "!!").replace("%", "!%")
                .replace("_", "!_") + "%";
    }

}
