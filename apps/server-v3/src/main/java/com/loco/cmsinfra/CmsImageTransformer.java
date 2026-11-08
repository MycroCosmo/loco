package com.loco.cmsinfra;

import com.cms.core.account.AdminProblem;
import com.cms.core.file.ImageTransformer;
import com.loco.images.codec.ImageConverter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Set;

public final class CmsImageTransformer implements ImageTransformer {
  private final ImageConverter converter;

  public CmsImageTransformer(ImageConverter converter) {
    this.converter = converter;
  }

  @Override
  public byte[] webp(byte[] source) {
    try {
      var metadata = converter.metadata(source);
      if (!Set.of("jpeg", "png", "webp", "avif").contains(metadata.format()))
        throw new AdminProblem(415, "Unsupported image format");
      if (metadata.width() < 1
          || metadata.height() < 1
          || (long) metadata.width() * metadata.height() > 40_000_000L)
        throw new AdminProblem(413, "Image pixel limit exceeded");
      var decoded = converter.read(source);
      double scale = Math.min(1.0, 2048.0 / Math.max(decoded.getWidth(), decoded.getHeight()));
      BufferedImage result = decoded;
      if (scale < 1) {
        result =
            new BufferedImage(
                Math.max(1, (int) Math.round(decoded.getWidth() * scale)),
                Math.max(1, (int) Math.round(decoded.getHeight() * scale)),
                BufferedImage.TYPE_INT_ARGB);
        var draw = result.createGraphics();
        try {
          draw.setRenderingHint(
              RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
          draw.drawImage(decoded, 0, 0, result.getWidth(), result.getHeight(), null);
        } finally {
          draw.dispose();
        }
      }
      // Existing low-level codec writes Q80/strip=true and reads the first frame; no LOCO crop.
      return converter.writeWebP(result);
    } catch (IOException invalid) {
      throw new AdminProblem(415, "Invalid image");
    }
  }
}
