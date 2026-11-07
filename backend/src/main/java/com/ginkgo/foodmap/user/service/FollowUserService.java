package com.ginkgo.foodmap.user.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.user.entity.FollowUserEntity;
import com.ginkgo.foodmap.user.exception.UserErrorCode;
import com.ginkgo.foodmap.user.mapper.FollowUserMapper;
import com.ginkgo.foodmap.user.vo.FollowPrivacyVo;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 用户状态与隐私公开协作；关系写入锁定两个用户，始终按数字ID升序避免互相关注反向取锁。 */
@Service
@RequiredArgsConstructor
public class FollowUserService {
    private final FollowUserMapper userMapper;

    @Transactional(readOnly = true)
    public FollowPrivacyVo privacy(long id) {
        var result = userMapper.privacy(id);
        if (result == null) {
            throw new BusinessException(UserErrorCode.PUBLIC_PROFILE_NOT_FOUND);
        }
        return result;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForFollow(long actor, long target, boolean active) {
        var lowerIdUser = userMapper.lock(Math.min(actor, target));
        var higherIdUser = userMapper.lock(Math.max(actor, target));
        var actorUser = actor < target ? lowerIdUser : higherIdUser;
        var targetUser = actor < target ? higherIdUser : lowerIdUser;
        if (!usable(actorUser)) {
            throw new BusinessException(UserErrorCode.PROFILE_UNAVAILABLE);
        }
        if (active && !usable(targetUser)) {
            throw new BusinessException(UserErrorCode.PUBLIC_PROFILE_NOT_FOUND);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FollowPrivacyVo set(long id, boolean following, boolean followers) {
        if (!usable(userMapper.lock(id))) {
            throw new BusinessException(UserErrorCode.PROFILE_UNAVAILABLE);
        }
        var current = privacy(id);
        if ((current.isFollowingPublic() != following || current.isFollowersPublic() != followers)
                && userMapper.update(id, following, followers) != 1) {
            throw new IllegalStateException("Follow privacy update failed");
        }
        current.setFollowingPublic(following);
        current.setFollowersPublic(followers);
        return current;
    }

    private boolean usable(FollowUserEntity user) {
        return user != null && user.isEmailVerified() && "ACTIVE".equals(user.getStatus());
    }
}
