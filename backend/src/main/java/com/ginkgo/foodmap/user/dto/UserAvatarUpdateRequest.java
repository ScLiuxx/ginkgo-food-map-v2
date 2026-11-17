package com.ginkgo.foodmap.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import lombok.Data;

/** 只接受已上传的文件逻辑ID，不允许客户端指定目标账号或OSS地址。 */
@Data
public class UserAvatarUpdateRequest {
    @NotNull
    @Pattern(regexp = "[1-9][0-9]{0,18}")
    private String fileId;
}
