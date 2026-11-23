package com.foodmap.file.image;

import lombok.Data;
import lombok.ToString;

/** 图片处理结果，不是数据库实体，也不作为HTTP响应暴露二进制内容。 */
@Data
public class ProcessedImage {
    @ToString.Exclude private byte[] bytes;
    private int width;
    private int height;
    private String sha256;
}
