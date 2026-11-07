package com.loco.auth;

import com.loco.compat.ComparisonCase;
import com.loco.compat.CompatibilityTestBase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@org.junit.jupiter.api.Tag("v2-comparison")
class RefreshComparisonTest extends CompatibilityTestBase {
  @ParameterizedTest(name = "{0}")
  @MethodSource("com.loco.auth.RefreshCases#cases")
  void compare(ComparisonCase testCase) throws Exception {
    runCase(testCase);
  }
}
