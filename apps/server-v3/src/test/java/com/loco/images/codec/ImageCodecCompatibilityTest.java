package com.loco.images.codec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("image-codec")
class ImageCodecCompatibilityTest {
    private static final long START = System.nanoTime();
    private static final CodecProbe PROBE = new CodecProbe(new ImageIoConverter(), Path.of(
            System.getProperty("codec.report.dir", "build/reports/codec/local")));
    private static final List<CodecProbe.Result> RESULTS = new ArrayList<>();

    @ParameterizedTest(name = "{0}: 입력 읽기와 WebP 쓰기")
    @ValueSource(strings = {"jpeg", "png", "webp", "heic", "avif"})
    void readsFixtureAndWritesDecodableWebP(String format) throws Exception {
        var result = PROBE.run(format);
        RESULTS.add(result);
        assertThat(result.status()).as(result.detail()).isNotEqualTo("FAIL");
        Assumptions.assumeTrue(result.status().equals("PASS"),
                "알려진 차이/제약: " + format + " / " + result.status() + " / v2 read=" + result.v2Read() + " / " + result.detail());
    }

    @AfterAll static void writeReport() throws Exception {
        PROBE.report(RESULTS, (System.nanoTime() - START) / 1_000_000);
    }
}
