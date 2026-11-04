package com.ginkgo.foodmap.restaurant.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 餐厅持久化读取模型，仅限 restaurant 模块内部使用，不直接序列化为接口响应。
 * 公开Mapper只读取展示列；受保护的写入模型通过独立子类补充归属，避免公开接口泄露。
 */
@Data
@TableName("restaurants")
public class RestaurantEntity {
    private Long id;
    private String name;
    private String categoryCode;
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
    private Instant createdAt;
    private Instant updatedAt;

    /** 分类同属本模块，读取时关联展示名，停用分类的历史餐厅仍可读。 */
    @TableField(exist = false)
    private String categoryName;

    /** GCJ-02 清水河参考点直线公里数，数据库全局排序用同一表达式。 */
    @TableField(exist = false)
    private Double distanceKm;

    /** 同属restaurant模块的统计投影；条数含历史，人数只计每账号最新有效点评。 */
    @TableField(exist = false)
    private long reviewCount;
    @TableField(exist = false)
    private long participantCount;
    /** 均分由累计值除以人数取得；null表示无人计分，真实0分必须保留。 */
    @TableField(exist = false)
    private BigDecimal score;
    @TableField(exist = false)
    private BigDecimal recommendRate;
    @TableField(exist = false)
    private BigDecimal taste;
    @TableField(exist = false)
    private BigDecimal value;
    @TableField(exist = false)
    private BigDecimal service;
    @TableField(exist = false)
    private BigDecimal ambience;
}
