package com.foodmap.restaurant.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 餐厅完整保存输入。金额先接收BigDecimal再校验整数，禁止把小数静默截断为整数元。 Unicode长度、价格组合与标签总长由服务统一校验；身份、来源、评分等字段不允许提交。 */
@Data
public class RestaurantSaveRequest {
    @NotBlank(message = "请输入餐厅名称")
    private String name;

    /** 是否必填由实际商户类型决定：普通餐厅必填，食堂必须为空。 */
    @Pattern(regexp = "[A-Z][A-Z0-9_]{0,31}", message = "分类编码格式错误")
    private String categoryCode;

    private BigDecimal pricePerPerson;

    @NotNull
    @Pattern(regexp = "UNKNOWN|EXACT|ESTIMATE", message = "人均类型错误")
    private String priceKind = "UNKNOWN";

    private String address;

    /** 系统统一使用GCJ-02，导入时完成转换，不再逐条保存或接受坐标系字段。 */
    @NotNull(message = "请确认餐厅纬度")
    private BigDecimal latitude;

    @NotNull(message = "请确认餐厅经度")
    private BigDecimal longitude;

    /** 管理员直接维护无需勾选，普通投稿和审核仍要求明确确认。 */
    private Boolean locationConfirmed;

    @JsonIgnore
    @AssertTrue(message = "请明确确认餐厅位置")
    public boolean isLocationConfirmationValid() {
        return this instanceof RestaurantDirectSaveRequest
                || Boolean.TRUE.equals(locationConfirmed);
    }

    @NotNull
    @Pattern(regexp = "OPERATING|CLOSED", message = "经营状态错误")
    private String businessStatus = "OPERATING";

    @NotNull
    @Size(max = 8, message = "最多提交8个标签")
    private List<@NotNull(message = "标签不能为空") String> tags = new ArrayList<>();

    /** 拒绝越权或拼错字段，不能把创建者、来源、评分等请求静默当成已保存。 */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("餐厅请求包含不支持的字段");
    }
}
