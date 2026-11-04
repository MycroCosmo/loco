package com.loco.images.codec;

import java.awt.image.BufferedImage;
import java.io.IOException;

/** 코덱 교체 경계. 프로필 크롭·HTTP·파일 저장 정책은 포함하지 않는다. */
public interface ImageConverter {
    record Metadata(String format, int width, int height) { }
    Metadata metadata(byte[] input) throws IOException;
    BufferedImage read(byte[] input) throws IOException;
    byte[] writeWebP(BufferedImage image) throws IOException;

    final class UnsupportedImage extends IOException {
        public UnsupportedImage() { super("No ImageIO reader accepts this input"); }
        public UnsupportedImage(String detail) { super(detail); }
    }
}
