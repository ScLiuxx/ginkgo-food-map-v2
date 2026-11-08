package com.ginkgo.foodmap.restaurant.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.restaurant.dto.CanteenWindowRequest;
import com.ginkgo.foodmap.restaurant.service.CanteenWindowService;
import com.ginkgo.foodmap.restaurant.vo.CanteenWindowViews;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 公开读路径与管理写路径明确分离，身份和评分均由服务端控制。 */
@RestController
@RequiredArgsConstructor
public class CanteenWindowController {
    private final CanteenWindowService windows;

    /** 公开完整窗口集合，已删除父食堂不可读取。 */
    @GetMapping("/api/restaurants/{id}/windows")
    public ResponseEntity<ApiResponse<List<CanteenWindowViews.Item>>> list(
            @PathVariable String id) {
        return response(windows.list(id, false));
    }

    /** 独立窗口详情，不使用餐厅ID代理窗口。 */
    @GetMapping("/api/canteen-windows/{id}")
    public ResponseEntity<ApiResponse<CanteenWindowViews.Item>> detail(@PathVariable String id) {
        return response(windows.detail(id));
    }

    /** 管理列表包含已删除项，权限由应用服务重复核验。 */
    @GetMapping("/api/admin/restaurants/{id}/windows")
    public ResponseEntity<ApiResponse<List<CanteenWindowViews.Item>>> manage(
            @PathVariable String id) {
        return response(windows.list(id, true));
    }

    /** 客户端UUID仅用于创建请求去重。 */
    @PostMapping("/api/admin/restaurants/{id}/windows")
    public ResponseEntity<ApiResponse<CanteenWindowViews.Item>> create(
            @PathVariable String id, @Valid @RequestBody CanteenWindowRequest request) {
        return response(windows.create(id, request));
    }

    /** 修改保持稳定窗口身份。 */
    @PutMapping("/api/admin/canteen-windows/{id}")
    public ResponseEntity<ApiResponse<CanteenWindowViews.Item>> update(
            @PathVariable String id, @Valid @RequestBody CanteenWindowRequest request) {
        return response(windows.update(id, request));
    }

    /** 软删除停止公开，不删除历史点评。 */
    @DeleteMapping("/api/admin/canteen-windows/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        windows.deleted(id, true);
        return ResponseEntity.noContent().build();
    }

    /** 恢复原窗口及原点评关联。 */
    @PostMapping("/api/admin/canteen-windows/{id}/restore")
    public ResponseEntity<Void> restore(@PathVariable String id) {
        windows.deleted(id, false);
        return ResponseEntity.noContent().build();
    }

    private <T> ResponseEntity<ApiResponse<T>> response(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
