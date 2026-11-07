package com.loco.places;

import static org.assertj.core.api.Assertions.assertThat;

import com.loco.compat.CompatibilityTestBase;
import java.util.*;
import org.junit.jupiter.api.*;

@Tag("v2-comparison")
class CoordinateKeysComparisonTest extends CompatibilityTestBase {
  @Test
  void prismaFloatRoundtripObservation() throws Exception {
    var values =
        new ArrayList<Double>(
            List.of(
                127.88879999999999,
                37.12345678901234,
                37.4444,
                0.0000001234567890123456,
                Double.MIN_VALUE));
    var random = new Random(43);
    for (int i = 0; i < 300; i++) values.add(random.nextDouble() * 360 - 180);
    var observed =
        environment()
            .nodeCheck(
                "const url=new URL(process.env.DATABASE_URL);url.username=process.env.DB_USERNAME;url.password=process.env.DB_PASSWORD;process.env.DATABASE_URL=url.toString();const {PrismaClient}=require('@prisma/client');const p=new PrismaClient();(async()=>{let a=[];for(const v of JSON.parse(process.argv[1])){const r=await p.$queryRawUnsafe('SELECT $1::float8 AS value, $1::text AS decimal',v);a.push([v,r[0].value,r[0].decimal]);}console.log(JSON.stringify(a));await p.$disconnect()})()",
                values);
    for (int i = 0; i < values.size(); i++)
      assertThat(PrismaFloats.input(values.get(i)))
          .as("Prisma float %s", observed.get(i))
          .isEqualTo(observed.get(i).get(1).doubleValue());
  }

  @Test
  void exactBinaryRoundingMatchesNodeIncludingNegativeZero() throws Exception {
    var values =
        new ArrayList<Double>(
            List.of(0.0, -0.0, -0.00001, 37.05, 1.005, 2.55, 90.0, -90.0, 180.0, -180.0));
    for (double v : new double[] {0.05, 0.005, 0.0005, 37.05, 37.005, 37.0005, 89.9995, 179.9995}) {
      for (double adjacent : new double[] {Math.nextDown(v), v, Math.nextUp(v)}) {
        values.add(adjacent);
        values.add(-adjacent);
      }
    }
    var random = new Random(42);
    for (int i = 0; i < 300; i++) values.add(random.nextDouble() * 360 - 180);
    // JSON은 -0의 부호를 잃을 수 있으므로 Node에서도 명시적으로 -0을 추가한다.
    var expected =
        environment()
            .nodeCheck(
                "const v=JSON.parse(process.argv[1]);v[1]=-0;console.log(JSON.stringify(v.map(n=>[1,2,3].map(p=>n.toFixed(p)))))",
                values);
    for (int i = 0; i < values.size(); i++)
      for (int p = 1; p <= 3; p++)
        assertThat(CoordinateKeys.fixed(values.get(i), p))
            .as("%s precision %s", values.get(i), p)
            .isEqualTo(expected.get(i).get(p - 1).asText());
    assertThat(CoordinateKeys.fixed(-0.00001, 1)).isEqualTo("-0.0");
    assertThat(CoordinateKeys.fixed(-0.0, 1)).isEqualTo("0.0");
  }
}
