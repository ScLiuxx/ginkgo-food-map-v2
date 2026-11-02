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

import java.util.ArrayList;
import java.math.BigDecimal;
import java.util.List;

/**
 * 公开目录的查询参数，排序与杯型只接受页面已有的稳定编码。
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

    @NotNull
    @Size(max = 6, message = "最多选择6种杯型")
    private List<@NotNull @Pattern(regexp = "bucket|super|large|medium|small|none",
            message = "杯型编码错误") String> tiers = new ArrayList<>();

    @NotNull
    @Pattern(regexp = "all|recommended|pitfall", message = "浏览范围错误")
    private String view = "all";

    @NotNull
    @Pattern(regexp = "score|taste|value|service|ambience|reviews|price|distance", message = "排序方式错误")
    private String sort = "score";

    @NotNull
    private Boolean hideClosed = false;

    /** 人均上限含边界，空值表示不限；未知价格由SQL排除，精度沿用金额字段。 */
    @DecimalMin(value = "0.01", message = "人均上限必须大于0")
    @Digits(integer = 8, fraction = 2, message = "人均上限最多8位整数和2位小数")
    private BigDecimal maxPrice;

    /** 距校区参考点的球面直线距离，单位米；空值不限，不依赖浏览器定位。 */
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
