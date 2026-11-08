package com.ginkgo.foodmap.restaurant.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/** restaurant模块私有窗口与自身统计投影；不直接返回持久化字段。 */
@Data
public class CanteenWindowEntity {
    private Long id;
    private Long canteenId;
    private String canteenName;
    private String campusCode;
    private String name;
    private Integer floorNumber;

    private String description;
    private Long coverFileId;
    private String priceKind;
    private Integer pricePerPerson;
    private String businessStatus;
    private Long createdBy;
    private Long updatedBy;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;
    private Long deletedBy;
    private byte[] clientRequestId;
    private byte[] requestHash;
    private BigDecimal score;
    private BigDecimal taste;
    private BigDecimal value;
    private BigDecimal service;
    private BigDecimal ambience;
    private BigDecimal recommendRate;
    private long reviewCount;
    private long participantCount;
    private String address;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private boolean needsLocation;
}
