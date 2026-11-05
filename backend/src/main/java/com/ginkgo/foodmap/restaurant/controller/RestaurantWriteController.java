package com.ginkgo.foodmap.restaurant.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.restaurant.dto.RestaurantDirectSaveRequest;
import com.ginkgo.foodmap.restaurant.service.RestaurantWriteService;
import com.ginkgo.foodmap.restaurant.vo.RestaurantManagementVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

/** 餐厅维护HTTP入口；身份和权限由安全链及Service校验，管理响应一律禁止缓存。 */
@RestController
@RequestMapping("/api/restaurants")
@RequiredArgsConstructor
public class RestaurantWriteController {
    private final RestaurantWriteService service;

    /** 仅管理员可读取管理资料，包含删除记录；不返回编辑版本。 */
    @GetMapping("/{id}/management")
    public ResponseEntity<ApiResponse<RestaurantManagementVo>> management(@PathVariable String id) {
        return ok(service.management(id));
    }

    /** 创建成功返回201及详情地址；不接受客户端指定ID或创建者。 */
    @PostMapping
    public ResponseEntity<ApiResponse<RestaurantManagementVo>> create(@Valid @RequestBody RestaurantDirectSaveRequest request) {
        var result = service.create(request);
        return ResponseEntity.created(URI.create("/api/restaurants/" + result.getRestaurant().getId()))
                .cacheControl(CacheControl.noStore()).body(ApiResponse.ok(result));
    }

    /** 完整保存，标签缺省为空数组并整体替换；并发保存以最后成功写入的完整内容为准。 */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<RestaurantManagementVo>> update(@PathVariable String id,
            @Valid @RequestBody RestaurantDirectSaveRequest request) {
        return ok(service.update(id, request));
    }

    /** 软删除无查询参数和请求体，只按当前授权及删除状态判断，不要求版本。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<RestaurantManagementVo>> delete(@PathVariable String id) {
        return ok(service.delete(id));
    }

    /** 恢复由Service方法级管理员授权保护，普通账号不能通过构造路径获得能力。 */
    @PostMapping("/{id}/restore")
    public ResponseEntity<ApiResponse<RestaurantManagementVo>> restore(@PathVariable String id) {
        return ok(service.restore(id));
    }

    private ResponseEntity<ApiResponse<RestaurantManagementVo>> ok(RestaurantManagementVo result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(result));
    }
}
