package com.foodmap.user.service;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.user.entity.UserProfileEntity;
import com.foodmap.user.exception.UserErrorCode;
import com.foodmap.user.mapper.UserProfileMapper;
import com.foodmap.user.policy.UserProfilePolicy;
import com.foodmap.user.policy.UserRegistrationPolicy;
import com.foodmap.user.vo.UserProfileVo;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 本人资料流程；身份由控制器认证上下文提供，不开放任意用户资料或邮箱修改能力。 */
@Service
@RequiredArgsConstructor
public class UserProfileService {
    private final UserProfileMapper mapper;
    private final UserRegistrationPolicy registrationPolicy;

    /** 只读无副作用，缺失账号不伪造空资料，也不返回未脱敏邮箱。 */
    @Transactional(readOnly = true)
    public UserProfileVo get(long userId) {
        return view(require(mapper.find(userId)));
    }

    /** 锁定当前账号后仅更新bio，重复保存相同值不写入、不推进既有行版本。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserProfileVo update(long userId, String input) {
        String bio = UserProfilePolicy.bio(input);
        var row = require(mapper.lock(userId));
        if (!bio.equals(row.getBio())) {
            if (mapper.updateBio(userId, bio) != 1) {
                throw new IllegalStateException("Profile update not confirmed");
            }
            row.setBio(bio);
        }
        return view(row);
    }

    /** 行锁串行化本账号改名，数据库唯一约束处理跨账号同时抢占同一用户名。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserProfileVo updateUsername(long userId, String input) {
        String username = registrationPolicy.normalizeUsername(input);
        var row = require(mapper.lock(userId));
        if (username.equals(row.getUsername())) {
            return view(row);
        }
        try {
            if (mapper.updateUsername(userId, username) != 1) {
                throw new IllegalStateException("Username update not confirmed");
            }
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(UserErrorCode.USERNAME_ALREADY_REGISTERED);
        }
        row.setUsername(username);
        return view(row);
    }

    private UserProfileEntity require(UserProfileEntity row) {
        if (row == null) {
            throw new BusinessException(UserErrorCode.PROFILE_UNAVAILABLE);
        }
        return row;
    }

    private UserProfileVo view(UserProfileEntity row) {
        var value = new UserProfileVo();
        value.setUserId(Long.toString(row.getId()));
        value.setUsername(row.getUsername());
        value.setMaskedEmail(UserProfilePolicy.maskedEmail(row.getEmail()));
        value.setBio(row.getBio());
        return value;
    }
}
