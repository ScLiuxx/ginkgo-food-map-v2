package com.ginkgo.foodmap.user.service;

import com.ginkgo.foodmap.common.exception.BusinessException;
import com.ginkgo.foodmap.file.service.MediaFileService;
import com.ginkgo.foodmap.security.service.CurrentActorService;
import com.ginkgo.foodmap.user.exception.UserErrorCode;
import com.ginkgo.foodmap.user.mapper.UserProfileMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 本人头像替换和公开读取；user只维护自己的关联，通过file服务处理文件状态及存储。 */
@Service
@RequiredArgsConstructor
public class UserAvatarService {
    private final UserProfileMapper profiles;
    private final MediaFileService files;
    private final CurrentActorService actors;

    /** 锁用户后替换文件，失败整体回滚；同一fileId重试不推进行版本，也不释放当前头像。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @PreAuthorize("isAuthenticated()")
    public void update(String value) {
        long userId = actors.requireActor().getUserId();
        long fileId = id(value);
        var profile = profiles.lock(userId);
        if (profile == null) {
            throw new BusinessException(UserErrorCode.PROFILE_UNAVAILABLE);
        }
        files.replaceAvatar(profile.getAvatarFileId(), fileId);
        if (!java.util.Objects.equals(profile.getAvatarFileId(), fileId)
                && profiles.updateAvatar(userId, fileId) != 1) {
            throw new IllegalStateException("Avatar update not confirmed");
        }
    }

    /** 读取当前关联，不暴露文件ID；无头像返回空，图片IO位于数据库事务外。 */
    public byte[] read(String value) {
        long userId = id(value);
        var profile = profiles.findAvatar(userId);
        if (profile == null) {
            throw new BusinessException(UserErrorCode.PUBLIC_PROFILE_NOT_FOUND);
        }
        return profile.getAvatarFileId() == null
                ? null
                : files.readAvatar(profile.getAvatarFileId(), userId);
    }

    private long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
                throw new NumberFormatException();
            }
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException(UserErrorCode.PUBLIC_PROFILE_INVALID);
        }
    }
}
