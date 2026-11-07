package com.loco.compat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HarnessTimingsTest {
  @Test
  void concurrentRequestsCountWallTimeOnce() {
    assertThat(
            HarnessTimings.union(
                List.of(new long[] {10, 40}, new long[] {20, 50}, new long[] {70, 90})))
        .isEqualTo(60);
  }

  @Test
  void nestedAndEmptyIntervalsDoNotInflateElapsedTime() {
    assertThat(HarnessTimings.union(List.of(new long[] {10, 50}, new long[] {20, 30})))
        .isEqualTo(40);
    assertThat(HarnessTimings.union(List.of())).isZero();
  }
}
