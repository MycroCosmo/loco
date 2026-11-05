package com.loco.places;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class CoordinateKeys {
  private CoordinateKeys() {}

  static String fixed(double value, int precision) {
    // valueOf의 십진 문자열은 JS가 반올림하는 정확한 이진 실수와 다르다.
    String magnitude =
        new BigDecimal(Math.abs(value)).setScale(precision, RoundingMode.HALF_UP).toPlainString();
    return value < 0 ? "-" + magnitude : magnitude;
  }
}
