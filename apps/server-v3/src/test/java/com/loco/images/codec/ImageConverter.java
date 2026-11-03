package com.loco.images.codec;

import java.awt.image.BufferedImage;
import java.io.IOException;

/** 코덱 검증용 경계. 프로필 크롭·HTTP·파일 저장 정책은 포함하지 않는다. */
public interface ImageConverter {
    BufferedImage read(byte[] input) throws IOException;
    byte[] writeWebP(BufferedImage image) throws IOException;

    final class UnsupportedImage extends IOException {
        public UnsupportedImage() { super("No ImageIO reader accepts this input"); }
    }
}
