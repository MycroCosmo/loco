package com.loco.compat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@org.junit.jupiter.api.Tag("v2-comparison")
public class ResponseComparisonTest extends CompatibilityTestBase {
  @ParameterizedTest(name = "{0}")
  @MethodSource("com.loco.compat.FoundationCases#cases")
  void compareFoundation(ComparisonCase testCase) throws Exception {
    runCase(testCase);
  }
}
