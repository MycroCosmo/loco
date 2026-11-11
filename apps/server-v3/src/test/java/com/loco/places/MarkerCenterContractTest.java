package com.loco.places;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class MarkerCenterContractTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void acceptsOnlyApprovedFiniteCenterDifferenceWithoutMutatingResponse() throws Exception {
    var expected =
        mapper.readTree(
            "[{\"id\":\"a\",\"count\":2,\"latitude\":37.1,\"longitude\":127.1,\"cellLatKey\":\"37.1\"}]");
    var actual = expected.deepCopy();
    ((ObjectNode) actual.get(0)).put("latitude", 37.1 + 5e-11);
    var original = actual.deepCopy();
    MarkerTestSupport.compareCellResponses(expected, actual);
    assertThat(actual).isEqualTo(original);
    for (String field : new String[] {"count", "cellLatKey", "id"}) {
      var changed = actual.deepCopy();
      ((ObjectNode) changed.get(0)).put(field, "different");
      assertThatThrownBy(() -> MarkerTestSupport.compareCellResponses(expected, changed))
          .isInstanceOf(AssertionError.class);
    }
    for (double center : new double[] {37.1 + 2e-10, Double.NaN, Double.POSITIVE_INFINITY}) {
      var changed = actual.deepCopy();
      ((ObjectNode) changed.get(0)).put("latitude", center);
      assertThatThrownBy(() -> MarkerTestSupport.compareCellResponses(expected, changed))
          .isInstanceOf(AssertionError.class);
    }
    var wrongType = actual.deepCopy();
    ((ObjectNode) wrongType.get(0)).put("longitude", "127.1");
    assertThatThrownBy(() -> MarkerTestSupport.compareCellResponses(expected, wrongType))
        .isInstanceOf(AssertionError.class);
    var extra = actual.deepCopy();
    ((ObjectNode) extra.get(0)).put("other", 1);
    assertThatThrownBy(() -> MarkerTestSupport.compareCellResponses(expected, extra))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void doesNotIgnoreVisibleMembershipOrArrayOrder() throws Exception {
    var expected =
        mapper.readTree(
            "[{\"id\":\"a\",\"latitude\":37,\"longitude\":127,\"count\":1},{\"id\":\"b\",\"latitude\":37,\"longitude\":127,\"count\":1}]");
    var reversed = mapper.createArrayNode().add(expected.get(1)).add(expected.get(0));
    assertThatThrownBy(() -> MarkerTestSupport.compareCellResponses(expected, reversed))
        .isInstanceOf(AssertionError.class);
    assertThatThrownBy(
            () ->
                MarkerTestSupport.compareCellResponses(
                    expected, mapper.createArrayNode().add(expected.get(0))))
        .isInstanceOf(AssertionError.class);
  }
}
