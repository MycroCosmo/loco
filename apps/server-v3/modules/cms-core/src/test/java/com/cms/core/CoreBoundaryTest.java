package com.cms.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreBoundaryTest {
  @TempDir Path directory;

  @Test
  void compilationAllowsCoreButRejectsHostTypes() throws Exception {
    assertThat(compile("class Probe { com.cms.core.CmsCoreConfiguration core; }"))
        .as("positive control: core is on the actual compilation classpath")
        .isTrue();
    assertThat(compile("class Probe { com.loco.LocoApplication host; }"))
        .as("a reverse dependency cannot compile")
        .isFalse();
    assertThat(compile("class Probe { software.amazon.awssdk.services.s3.S3Client storage; }"))
        .as("S3 SDK belongs to the host adapter, never core")
        .isFalse();
  }

  @Test
  void runtimeHasNoHostClassesOrBaseline() {
    assertThatThrownBy(() -> Class.forName("com.loco.LocoApplication"))
        .isInstanceOf(ClassNotFoundException.class);
    assertThatThrownBy(() -> Class.forName("software.amazon.awssdk.services.s3.S3Client"))
        .isInstanceOf(ClassNotFoundException.class);
    assertThat(getClass().getResource("/db/migration/V1__baseline.sql")).isNull();
    assertThat(getClass().getResource("/db/cms/V2__create_cms_schema.sql")).isNotNull();
  }

  private boolean compile(String source) throws Exception {
    Path file = directory.resolve("Probe.java");
    Files.writeString(file, source);
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertThat(compiler).as("Java 21 JDK is required").isNotNull();
    try (var manager = compiler.getStandardFileManager(null, null, null)) {
      return compiler
          .getTask(
              null,
              manager,
              new DiagnosticCollector<JavaFileObject>(),
              java.util.List.of(
                  "-proc:none", "-classpath", System.getProperty("cms.compile.classpath")),
              null,
              manager.getJavaFileObjects(file))
          .call();
    }
  }
}
