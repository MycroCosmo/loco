package com.loco.cmsinfra;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cms.core.account.AdminProblem;
import com.loco.images.codec.ImageConverter;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class CmsImageTransformerTest {
  @Test
  void pixelLimitIsCheckedBeforeDecoding() throws Exception {
    var codec = mock(ImageConverter.class);
    byte[] bytes = {1};
    when(codec.metadata(bytes)).thenReturn(new ImageConverter.Metadata("png", 8000, 5001));
    assertThatThrownBy(() -> new CmsImageTransformer(codec).webp(bytes))
        .isInstanceOfSatisfying(AdminProblem.class, e -> assertThat(e.status()).isEqualTo(413));
    verify(codec, never()).read(any());
  }

  @Test
  void resizePreservesAspectAndDoesNotEnlarge() throws Exception {
    for (int width : new int[] {4000, 100}) {
      var codec = mock(ImageConverter.class);
      byte[] bytes = {1};
      var source = new BufferedImage(width, width / 2, BufferedImage.TYPE_INT_RGB);
      when(codec.metadata(bytes)).thenReturn(new ImageConverter.Metadata("png", width, width / 2));
      when(codec.read(bytes)).thenReturn(source);
      when(codec.writeWebP(any()))
          .thenAnswer(
              call -> {
                BufferedImage output = call.getArgument(0);
                assertThat(output.getWidth()).isEqualTo(Math.min(2048, width));
                assertThat(output.getHeight()).isEqualTo(Math.min(1024, width / 2));
                return new byte[] {2};
              });
      assertThat(new CmsImageTransformer(codec).webp(bytes)).containsExactly((byte) 2);
    }
  }
}
