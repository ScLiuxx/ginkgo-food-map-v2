package com.foodmap.review.service;

import com.foodmap.review.dto.RestaurantReviewPhotos;
import com.foodmap.review.entity.ReviewPhotoCandidate;
import com.foodmap.review.mapper.ReviewPhotoMapper;
import com.foodmap.user.service.PublicAuthorService;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 父商户由restaurant服务校验；review只查询自己的点评与照片，不跨模块访问表。 */
@Service
@RequiredArgsConstructor
public class RestaurantReviewPhotoService {
    private final ReviewPhotoMapper photos;
    private final PublicAuthorService authors;

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public RestaurantReviewPhotos.Page page(long restaurant, int page) {
        var rows = photos.candidates(restaurant, (page - 1) * 12);
        boolean more = rows.size() > 12;
        rows = rows.subList(0, Math.min(12, rows.size()));
        var names =
                authors.find(
                        rows.stream()
                                .map(ReviewPhotoCandidate::getAuthorUserId)
                                .distinct()
                                .toList());
        var result = new RestaurantReviewPhotos.Page();
        result.setHasMore(more);
        result.setRecords(
                rows.stream()
                        .map(
                                row -> {
                                    var item = new RestaurantReviewPhotos.Item();
                                    item.setReviewId(Long.toString(row.getReviewId()));
                                    item.setPosition(row.getPosition());
                                    item.setUrl(
                                            "/api/reviews/"
                                                    + row.getReviewId()
                                                    + "/photos/"
                                                    + row.getPosition());
                                    var author = names.get(Long.toString(row.getAuthorUserId()));
                                    item.setUsername(
                                            author == null ? "用户不可用" : author.getUsername());
                                    item.setBody(row.getBody());
                                    item.setCreatedAt(row.getCreatedAt());
                                    return item;
                                })
                        .toList());
        return result;
    }
}
