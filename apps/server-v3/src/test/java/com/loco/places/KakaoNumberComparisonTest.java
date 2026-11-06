package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.CompatibilityTestBase;
import java.util.*;
import org.junit.jupiter.api.*;

@Tag("v2-comparison")
class KakaoNumberComparisonTest extends CompatibilityTestBase {
  @Test
  void queryNumbersUseNodeShortestDecimalAndExponentBoundaries() throws Exception {
    var values =
        new ArrayList<Double>(
            List.of(
                0d,
                -0d,
                1e-6,
                Math.nextDown(1e-6),
                1e21,
                Math.nextDown(1e21),
                1e23,
                1000000000000000128d,
                Double.MIN_VALUE,
                Double.MAX_VALUE));
    var random = new Random(815);
    while (values.size() < 1010) {
      double v = Double.longBitsToDouble(random.nextLong());
      if (Double.isFinite(v)) values.add(v);
    }
    var node =
        environment()
            .nodeCheck(
                "process.stdout.write(JSON.stringify(JSON.parse(process.argv[1]).map(String)))",
                values);
    for (int i = 0; i < values.size(); i++)
      assertThat(KakaoSearchInputs.number(values.get(i)))
          .as("value=%s", values.get(i))
          .isEqualTo(node.get(i).asText());
  }
}
