package com.foodmap.file;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.file.image.JpegImageProcessor;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import static org.assertj.core.api.Assertions.*;

/** 使用真实解码器验证伪格式、超限和去附加内容，不以文件扩展名模拟验证成功。 */
class JpegImageProcessorTest {
    private final JpegImageProcessor policy = new JpegImageProcessor();
    static byte[] jpeg(int width,int height) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB),"jpeg",out);
        return out.toByteArray();
    }
    @Test void validatesAndReencodesActualJpeg() throws Exception {
        var out = new ByteArrayOutputStream();
        out.write(jpeg(1200,20));out.write("private-exif-or-trailing-data".getBytes());
        var image = policy.process(new ByteArrayInputStream(out.toByteArray()));
        assertThat(image.getWidth()).isEqualTo(1200);assertThat(image.getHeight()).isEqualTo(20);
        assertThat(image.getSha256()).hasSize(64);
        assertThat(new String(image.getBytes(),java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("private-exif-or-trailing-data");
        assertThat(ImageIO.read(new ByteArrayInputStream(image.getBytes()))).isNotNull();
    }
    @Test void rejectsEmptyAndOversizedFiles() {
        for(var bytes:new byte[][]{new byte[0],new byte[JpegImageProcessor.MAX_BYTES+1]})
            assertThatThrownBy(()->policy.process(new ByteArrayInputStream(bytes))).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsFakeImagesAndUnsupportedRealPng() throws Exception {
        assertThatThrownBy(()->policy.process(new ByteArrayInputStream("<svg/>".getBytes()))).isInstanceOf(BusinessException.class);
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(10,10,BufferedImage.TYPE_INT_RGB),"png",png);
        assertThatThrownBy(()->policy.process(new ByteArrayInputStream(png.toByteArray()))).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsHugeDimensionsBeforePixelDecode() throws Exception {
        byte[] bytes=jpeg(1201,1);
        assertThatThrownBy(()->policy.process(new ByteArrayInputStream(bytes))).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsTruncatedJpeg() throws Exception {
        byte[] bytes=jpeg(30,30);
        assertThatThrownBy(()->policy.process(new ByteArrayInputStream(java.util.Arrays.copyOf(bytes,bytes.length/2))))
                .isInstanceOf(BusinessException.class);
    }
}
