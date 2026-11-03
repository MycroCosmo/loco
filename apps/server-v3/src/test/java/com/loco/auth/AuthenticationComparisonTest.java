package com.loco.auth;

import com.loco.compat.ComparisonCase;
import com.loco.compat.CompatibilityTestBase;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AuthenticationComparisonTest extends CompatibilityTestBase {
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.loco.auth.AuthenticationCases#errors")
    void compare(ComparisonCase testCase) throws Exception { runCase(testCase); }
}
