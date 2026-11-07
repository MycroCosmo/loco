package com.loco.places;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Prisma 5 bigdecimal 0.3의 Float 변환: 정확한 double을 유효숫자 16자리로 반올림한다. */
final class PrismaFloats {
  private static final MathContext PRECISION = new MathContext(16, RoundingMode.HALF_EVEN);

  private PrismaFloats() {}

  static double input(double value) {
    return new BigDecimal(value).round(PRECISION).doubleValue();
  }
}
