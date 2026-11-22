package com.foodmap.restaurant.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 管理员直接维护完整业务资料；独立扩展避免改变历史投稿的序列化及幂等摘要。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RestaurantDirectSaveRequest extends RestaurantSaveRequest {
    /** 缺省保留相册，空列表清空；首项为封面，关联及文件归属由服务端验证。 */
    @Size(max = 1000)
    private List<@NotNull @Valid RestaurantPhotoSelection> photos;

    /** 新页面显式传类型；兼容旧客户端省略时，新建普通餐厅，编辑保持原类型和用餐方式。 */
    @Pattern(regexp = "REGULAR|CANTEEN", message = "餐厅类型错误")
    private String restaurantType;

    /** 显式类型模式下null表示未知，false表示明确不支持；不能用COALESCE吞掉清空操作。 */
    private Boolean supportsDineIn;

    private Boolean supportsDelivery;

    /** 旧输入模式不允许单独提交用餐方式，以免客户端误以为字段已保存。 */
    @JsonIgnore
    @AssertTrue(message = "修改堂食或外卖时请同时提供餐厅类型")
    public boolean isDiningTypeConsistent() {
        return restaurantType != null || (supportsDineIn == null && supportsDelivery == null);
    }
}
