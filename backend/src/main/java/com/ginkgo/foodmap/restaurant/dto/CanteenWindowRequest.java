package com.ginkgo.foodmap.restaurant.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;

import java.util.UUID;

/** 窗口资料白名单；所属食堂由路径指定，评分及操作身份不允许客户端写入。 */
@Data
public class CanteenWindowRequest {
    private UUID clientRequestId;

    @NotBlank
    @Size(max = 40)
    private String name;

    @NotNull
    @Min(-10)
    @Max(100)
    private Integer floorNumber;

    @Size(max = 500)
    private String description;

    @NotNull
    @Pattern(regexp = "UNKNOWN|EXACT|ESTIMATE")
    private String priceKind = "UNKNOWN";

    @Min(1)
    @Max(5000)
    private Integer pricePerPerson;

    @NotNull
    @Pattern(regexp = "OPERATING|CLOSED")
    private String businessStatus = "OPERATING";

    /** 不接受所属食堂、评分、创建者等额外字段，避免客户端误以为可以修改服务端事实。 */
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("窗口资料包含不支持的字段");
    }
}
