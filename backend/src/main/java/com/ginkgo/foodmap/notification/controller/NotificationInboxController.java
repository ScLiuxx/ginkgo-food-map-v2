package com.ginkgo.foodmap.notification.controller;

import com.ginkgo.foodmap.common.api.ApiResponse;
import com.ginkgo.foodmap.notification.dto.NotificationQuery;
import com.ginkgo.foodmap.notification.service.NotificationInboxService;
import com.ginkgo.foodmap.notification.vo.NotificationViews;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

/** 收件箱没有发送HTTP入口，客户端只能读取本人消息和改变本人已读状态。 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@RequestMapping("/api/me/notifications")
public class NotificationInboxController {
    private final NotificationInboxService inbox;

    /** 账号、offset与排序由服务端控制，客户端仅能选择已开放分类和有界分页。 */
    @InitBinder("notificationQuery")
    void bind(WebDataBinder binder) {
        binder.setAllowedFields("category", "page", "pageSize");
    }

    /** 返回本人有界分页与未读数，页面读取不会改变阅读状态，私有响应禁止缓存。 */
    @GetMapping
    public ResponseEntity<ApiResponse<NotificationViews.Page>> page(
            @Valid @ModelAttribute NotificationQuery query) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(inbox.page(query)));
    }

    /** 单条已读成功返回无响应体的204，归属及首次阅读时间由业务服务保证。 */
    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable String id) {
        inbox.markRead(id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** 首页使用ALL，系统分类使用SYSTEM；校验与列表一致，空收件箱也可幂等调用。 */
    @PostMapping("/read-all")
    public ResponseEntity<Void> readAll(@Valid @ModelAttribute NotificationQuery query) {
        inbox.markAllRead(query.getCategory());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
