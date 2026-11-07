package com.loco.images.codec;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

public final class ImageIoConverter implements ImageConverter {
  @Override
  public Metadata metadata(byte[] bytes) throws IOException {
    try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new UnsupportedImage();
      var reader = readers.next();
      try {
        reader.setInput(input);
        return new Metadata(
            reader.getFormatName().toLowerCase(java.util.Locale.ROOT),
            reader.getWidth(0),
            reader.getHeight(0));
      } finally {
        reader.dispose();
      }
    }
  }

  @Override
  public BufferedImage read(byte[] bytes) throws IOException {
    try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) throw new UnsupportedImage();
      var reader = readers.next();
      try {
        reader.setInput(input);
        return reader.read(0);
      } finally {
        reader.dispose();
      }
    }
  }

  @Override
  public byte[] writeWebP(BufferedImage image) throws IOException {
    var writers = ImageIO.getImageWritersByFormatName("webp");
    if (!writers.hasNext()) throw new IOException("No WebP writer");
    var writer = writers.next();
    var bytes = new ByteArrayOutputStream();
    try (var output = new MemoryCacheImageOutputStream(bytes)) {
      var parameters = writer.getDefaultWriteParam();
      parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      parameters.setCompressionType("Lossy");
      parameters.setCompressionQuality(0.8f);
      writer.setOutput(output);
      writer.write(null, new IIOImage(image, null, null), parameters);
      output.flush();
      return bytes.toByteArray();
    } finally {
      writer.dispose();
    }
  }
}
