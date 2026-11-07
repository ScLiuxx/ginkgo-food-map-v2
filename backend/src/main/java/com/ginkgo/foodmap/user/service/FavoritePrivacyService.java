package com.ginkgo.foodmap.user.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.user.exception.UserErrorCode;
import com.ginkgo.foodmap.user.mapper.FavoritePrivacyMapper;
import com.ginkgo.foodmap.user.vo.FavoriteVisibilityVo;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** 收藏隐私由user模块管理；只向可信应用服务提供访问判定，不暴露用户持久化对象。 */
@Service
@RequiredArgsConstructor
public class FavoritePrivacyService {
    private final FavoritePrivacyMapper mapper;

    @Transactional(readOnly = true)
    public FavoriteVisibilityVo get(long user) {
        var result = mapper.find(user);
        if (result == null) {
            throw new BusinessException(UserErrorCode.PUBLIC_PROFILE_NOT_FOUND);
        }
        return result;
    }

    /** 参与列表只读事务，保证权限、计数与记录采用同一快照；每次HTTP读取重新核验。 */
    @Transactional(readOnly = true)
    public boolean canView(long user, long viewer) {
        var settings = get(user);
        return user == viewer || settings.isFavoritesPublic();
    }

    /** 幂等保存不推进版本；真实修改只更新该字段及行版本，不改凭证。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FavoriteVisibilityVo set(long user, boolean value) {
        var result = mapper.lock(user);
        if (result == null) {
            throw new BusinessException(UserErrorCode.PROFILE_UNAVAILABLE);
        }
        if (result.isFavoritesPublic() != value && mapper.update(user, value) != 1) {
            throw new IllegalStateException("Privacy update failed");
        }
        result.setFavoritesPublic(value);
        return result;
    }
}
