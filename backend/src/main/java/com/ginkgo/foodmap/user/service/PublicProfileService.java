package com.ginkgo.foodmap.user.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.growth.service.GrowthQueryService;
import com.ginkgo.foodmap.review.dto.ReviewListQuery;
import com.ginkgo.foodmap.review.service.ContentLikeService;
import com.ginkgo.foodmap.review.service.MyReviewReadService;
import com.ginkgo.foodmap.review.vo.MyReviewPageVo;
import com.ginkgo.foodmap.user.exception.UserErrorCode;
import com.ginkgo.foodmap.user.mapper.PublicProfileMapper;
import com.ginkgo.foodmap.user.vo.PublicProfileVo;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 登录可见主页的跨模块组装，资料、等级与获赞在同一RR快照读取，不产生账户或奖励。 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PublicProfileService {
    private final PublicProfileMapper profiles;
    private final GrowthQueryService growth;
    private final ContentLikeService likes;
    private final MyReviewReadService reviews;

    /** 返回固定白名单，内部服务提供等级和有效获赞，不暴露持久化对象。 */
    public PublicProfileVo get(String value) {
        long id = id(value);
        var profile = require(id);
        profile.setLevel(growth.summary(id).getLevel());
        profile.setReceivedLikes(likes.receivedByAuthor(id));
        return profile;
    }

    /** 主体存在性先于点评读取；复用可见店铺过滤及每人每店最新代表，不读取投稿申请。 */
    public MyReviewPageVo reviews(String value, ReviewListQuery query) {
        long id = id(value);
        require(id);
        return reviews.list(id, query);
    }

    private PublicProfileVo require(long id) {
        var profile = profiles.find(id);
        if (profile == null) {
            throw new BusinessException(UserErrorCode.PUBLIC_PROFILE_NOT_FOUND);
        }
        return profile;
    }

    private long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new BusinessException(UserErrorCode.PUBLIC_PROFILE_INVALID);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException(UserErrorCode.PUBLIC_PROFILE_INVALID);
        }
    }
}
