package com.ginkgo.foodmap.restaurant.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.restaurant.dto.WindowPhotoRequest;
import com.ginkgo.foodmap.restaurant.service.WindowPhotoService;
import com.ginkgo.foodmap.restaurant.vo.WindowPhotoView;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 窗口图片明确区分匿名读取与管理员写入，不暴露OSS地址。 */
@RestController
@RequiredArgsConstructor
public class WindowPhotoController {
    private final WindowPhotoService service;

    /** 公开相册按入册顺序返回，首张作为封面。 */
    @GetMapping("/api/canteen-windows/{id}/photos")
    public ResponseEntity<ApiResponse<List<WindowPhotoView>>> list(@PathVariable String id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(service.list(id)));
    }

    /** 每次读取均核对父食堂、窗口及图片关联，不允许浏览器缓存绕过后续隐藏。 */
    @GetMapping("/api/canteen-windows/{id}/photos/{fileId}")
    public ResponseEntity<byte[]> read(@PathVariable String id, @PathVariable String fileId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_JPEG)
                .header("X-Content-Type-Options", "nosniff")
                .body(service.read(id, fileId));
    }

    /** 使用当前管理员本人上传的WINDOW回执入册，重复请求返回当前相册。 */
    @PostMapping("/api/admin/canteen-windows/{id}/photos")
    public ResponseEntity<ApiResponse<List<WindowPhotoView>>> add(
            @PathVariable String id, @Valid @RequestBody WindowPhotoRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(service.add(id, request.getFileId())));
    }

    /** 幂等删除相册关联，首张移除后自动更新封面。 */
    @DeleteMapping("/api/admin/canteen-windows/{id}/photos/{fileId}")
    public ResponseEntity<Void> remove(@PathVariable String id, @PathVariable String fileId) {
        service.remove(id, fileId);
        return ResponseEntity.noContent().build();
    }
}
