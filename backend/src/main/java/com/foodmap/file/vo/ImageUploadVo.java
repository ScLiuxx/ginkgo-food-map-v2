package com.foodmap.file.vo;

import lombok.Data;
import java.time.Instant;

/** 上传结果只含文件ID和验证后的元数据，写评期间仍使用浏览器本地预览。 */
@Data
public class ImageUploadVo {
    private String fileId;
    private int byteSize;
    private int width;
    private int height;
    private Instant expiresAt;
}
