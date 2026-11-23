package com.foodmap.restaurant.controller;

import com.foodmap.common.api.ApiResponse;
import com.foodmap.restaurant.dto.FavoriteListQuery;
import com.foodmap.restaurant.service.RestaurantFavoriteService;
import com.foodmap.restaurant.support.RestaurantIdentifiers;
import com.foodmap.restaurant.vo.FavoriteViews;
import com.foodmap.security.service.CurrentActorService;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

/** 收藏及公开列表均要求登录，未放行任何匿名安全路径，不接受客户端指定写入用户。 */
@RestController
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class RestaurantFavoriteController {
    private final RestaurantFavoriteService favorites;
    private final CurrentActorService actors;

    @InitBinder("favoriteListQuery")
    void bind(WebDataBinder binder) {
        binder.setAllowedFields("page", "pageSize");
    }

    @GetMapping("/api/restaurants/{id}/favorite")
    public ResponseEntity<ApiResponse<FavoriteViews.State>> state(@PathVariable String id) {
        return response(favorites.state(actors.requireActor().getUserId(), id));
    }

    @PutMapping("/api/restaurants/{id}/favorite")
    public ResponseEntity<ApiResponse<FavoriteViews.State>> add(@PathVariable String id) {
        return response(favorites.set(actors.requireActor().getUserId(), id, true));
    }

    @DeleteMapping("/api/restaurants/{id}/favorite")
    public ResponseEntity<ApiResponse<FavoriteViews.State>> remove(@PathVariable String id) {
        return response(favorites.set(actors.requireActor().getUserId(), id, false));
    }

    @GetMapping("/api/me/favorites")
    public ResponseEntity<ApiResponse<FavoriteViews.Page>> mine(
            @Valid @ModelAttribute FavoriteListQuery query) {
        long user = actors.requireActor().getUserId();
        return response(favorites.list(user, user, query));
    }

    @GetMapping("/api/users/{id}/favorites")
    public ResponseEntity<ApiResponse<FavoriteViews.Page>> user(
            @PathVariable String id, @Valid @ModelAttribute FavoriteListQuery query) {
        return response(
                favorites.list(
                        actors.requireActor().getUserId(), RestaurantIdentifiers.id(id), query));
    }

    private <T> ResponseEntity<ApiResponse<T>> response(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(value));
    }
}
