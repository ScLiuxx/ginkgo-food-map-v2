package com.ginkgo.foodmap.review.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.restaurant.service.RestaurantReadService;
import com.ginkgo.foodmap.review.dto.ReviewHistoryQuery;
import com.ginkgo.foodmap.review.dto.ReviewListQuery;
import com.ginkgo.foodmap.review.entity.ReviewPhotoReferenceEntity;
import com.ginkgo.foodmap.review.entity.ReviewReadEntity;
import com.ginkgo.foodmap.review.exception.ReviewErrorCode;
import com.ginkgo.foodmap.review.mapper.ReviewPhotoMapper;
import com.ginkgo.foodmap.review.mapper.ReviewReadMapper;
import com.ginkgo.foodmap.review.policy.ReviewHistoryCursorPolicy;
import com.ginkgo.foodmap.review.policy.ReviewReadPolicy;
import com.ginkgo.foodmap.review.vo.ReviewHistoryVo;
import com.ginkgo.foodmap.review.vo.ReviewPageVo;
import com.ginkgo.foodmap.user.dto.PublicAuthor;
import com.ginkgo.foodmap.user.service.PublicAuthorService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/** 点评公开读取流程；父店、计数、主卡及作者/照片均来自同一只读快照。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ReviewReadService {
    private final RestaurantReadService restaurants;
    private final ReviewReadMapper mapper;
    private final ReviewPhotoMapper photos;
    private final PublicAuthorService authors;
    private final com.ginkgo.foodmap.growth.service.GrowthQueryService growth;

    /** 不持写锁，不访问OSS；默认排序在分页前批量取得等级，空页省去批量补全查询。 */
    public ReviewPageVo list(String restaurantId, ReviewListQuery query) {
        long restaurant = ReviewReadPolicy.restaurantId(restaurantId);
        restaurants.requireVisible(restaurant);
        var counts = mapper.counts(restaurant);
        var result = new ReviewPageVo();
        result.setTotalGroups(counts.getTotalGroups());
        result.setTotalReviews(counts.getTotalReviews());
        result.setPage(query.getPage());
        result.setPageSize(query.getPageSize());
        result.setTotalPages(
                result.getTotalGroups() / query.getPageSize()
                        + (result.getTotalGroups() % query.getPageSize() == 0 ? 0 : 1));
        if (query.getOffset() >= result.getTotalGroups()) {
            return result;
        }
        // 最新代表独立于排序；历史点评的赞不合并到当前代表。
        var rows =
                "TIME".equals(query.getSort())
                        ? mapper.page(restaurant, query.getPageSize(), query.getOffset())
                        : mapper.rankedPage(
                                restaurant,
                                query.getPageSize(),
                                query.getOffset(),
                                growth.authorLevels(mapper.authorIds(restaurant)));
        var names = authors.find(rows.stream().map(ReviewReadEntity::getAuthorUserId).toList());
        var references =
                photos.references(rows.stream().map(ReviewReadEntity::getId).toList()).stream()
                        .collect(Collectors.groupingBy(ReviewPhotoReferenceEntity::getReviewId));
        result.setRecords(
                rows.stream()
                        .map(
                                row -> {
                                    var group = new ReviewPageVo.Group();
                                    // 逻辑用户ID没有跨模块外键；缺失账号仍保留稳定归组身份，不伪造用户名。
                                    var author = names.get(Long.toString(row.getAuthorUserId()));
                                    if (author == null) {
                                        author = new PublicAuthor();
                                        author.setId(Long.toString(row.getAuthorUserId()));
                                        author.setUsername("用户不可用");
                                    }
                                    group.setAuthor(author);
                                    group.setHistoryCount(row.getHistoryCount());
                                    if (row.getHistoryCount() > 0) {
                                        group.setNextHistoryCursor(
                                                ReviewHistoryCursorPolicy.encode(
                                                        restaurant,
                                                        row.getAuthorUserId(),
                                                        row.getCreatedAt(),
                                                        row.getId()));
                                    }
                                    group.setCurrentReview(
                                            current(
                                                    row,
                                                    references.getOrDefault(
                                                            row.getId(), List.of())));
                                    return group;
                                })
                        .toList());
        return result;
    }

    /** 历史按需读取，同一快照核验父店、最新代表、锚点和本段照片；不加载OSS字节。 */
    public ReviewHistoryVo history(String restaurantId, String authorId, ReviewHistoryQuery query) {
        long restaurant = ReviewReadPolicy.restaurantId(restaurantId);
        long author = ReviewReadPolicy.restaurantId(authorId);
        restaurants.requireVisible(restaurant);
        var latest = mapper.latest(restaurant, author);
        ReviewReadEntity boundary = latest;
        if (query.getCursor() != null) {
            var decoded = ReviewHistoryCursorPolicy.decode(query.getCursor(), restaurant, author);
            boundary = mapper.anchor(restaurant, author, decoded.getId());
            if (boundary == null || !boundary.getCreatedAt().equals(decoded.getCreatedAt())) {
                throw new BusinessException(ReviewErrorCode.INVALID_CURSOR);
            }
        }
        var result = new ReviewHistoryVo();
        if (latest == null) {
            return result;
        }
        result.setCurrentReviewId(latest.getId().toString());
        var name = authors.find(List.of(author)).get(Long.toString(author));
        if (name == null) {
            name = new PublicAuthor();
            name.setId(Long.toString(author));
            name.setUsername("用户不可用");
        }
        result.setAuthor(name);
        var rows =
                mapper.history(
                        restaurant,
                        author,
                        boundary.getCreatedAt(),
                        boundary.getId(),
                        query.getPageSize() + 1);
        boolean more = rows.size() > query.getPageSize();
        rows = rows.subList(0, Math.min(rows.size(), query.getPageSize()));
        if (!rows.isEmpty()) {
            var refs =
                    photos.references(rows.stream().map(ReviewReadEntity::getId).toList()).stream()
                            .collect(
                                    Collectors.groupingBy(ReviewPhotoReferenceEntity::getReviewId));
            result.setRecords(
                    rows.stream()
                            .map(
                                    row -> {
                                        var record =
                                                current(
                                                        row,
                                                        refs.getOrDefault(row.getId(), List.of()));
                                        record.setScoringStatus("history");
                                        return record;
                                    })
                            .toList());
            if (more) {
                var last = rows.getLast();
                result.setNextCursor(
                        ReviewHistoryCursorPolicy.encode(
                                restaurant, author, last.getCreatedAt(), last.getId()));
            }
        }
        result.setHasMore(more);
        return result;
    }

    /** 同模块本人列表复用相同字段和照片URL映射，避免两套点评展示协议漂移。 */
    static ReviewPageVo.CurrentReview current(
            ReviewReadEntity row, List<ReviewPhotoReferenceEntity> references) {
        var review = new ReviewPageVo.CurrentReview();
        review.setId(row.getId().toString());
        var scores = new ReviewPageVo.Scores();
        scores.setTaste(row.getTasteScore());
        scores.setValue(row.getValueScore());
        scores.setService(row.getServiceScore());
        scores.setAmbience(row.getAmbienceScore());
        review.setScores(scores);
        review.setOverallScore(row.getOverallScore());
        review.setRecommended(row.isRecommended());
        review.setBody(row.getBody());
        review.setCreatedAt(row.getCreatedAt());
        review.setPhotos(
                references.stream()
                        .map(
                                ref -> {
                                    var photo = new ReviewPageVo.Photo();
                                    photo.setId(Long.toString(ref.getMediaFileId()));
                                    photo.setPosition(ref.getPosition());
                                    photo.setUrl(
                                            "/api/reviews/"
                                                    + row.getId()
                                                    + "/photos/"
                                                    + ref.getPosition());
                                    return photo;
                                })
                        .toList());
        return review;
    }
}
