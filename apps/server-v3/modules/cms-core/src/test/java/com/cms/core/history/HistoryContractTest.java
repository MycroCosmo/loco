package com.cms.core.history;

import static org.assertj.core.api.Assertions.*;

import com.cms.core.account.AdminProblem;
import com.cms.core.permission.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HistoryContractTest {
  final PermissionCatalog permissions =
      new PermissionCatalog(
          List.of(
              () ->
                  List.of(
                      new PermissionCatalog.Definition(
                          "sample.documents", "Documents", Set.of(Action.READ), false))));

  HistoryContract contract(String resource, int version, boolean current, String field) {
    return new HistoryContract(
        resource,
        version,
        current,
        "sample.documents",
        Set.of("CHANGE"),
        Map.of(field, new HistoryContract.Field(HistoryContract.Kind.BOOLEAN, false, 1, Set.of())));
  }

  HistoryContractRegistry registry(HistoryContract... contracts) {
    return new HistoryContractRegistry(List.of(() -> List.of(contracts)), permissions);
  }

  @Test
  void detachesAndRejectsAllOtherShapes() {
    var r = registry(contract("sample.documents", 1, true, "enabled"));
    var input = new HashMap<String, Object>(Map.of("enabled", true));
    var p = r.writing("sample.documents", "CHANGE", input, input);
    input.put("enabled", false);
    assertThat(p.after()).containsEntry("enabled", true);
    for (Object value :
        List.of("true", 1, List.of(true), Map.of("password", "secret"), new byte[] {1}, Double.NaN))
      assertThatThrownBy(
              () ->
                  r.writing(
                      "sample.documents",
                      "CHANGE",
                      Map.of("enabled", value),
                      Map.of("enabled", true)))
          .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> r.writing("sample.documents", "CHANGE", Map.of(), Map.of("enabled", true)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                r.writing(
                    "sample.documents",
                    "CHANGE",
                    Map.of("enabled", true, "extra", false),
                    Map.of("enabled", true)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> r.writing("sample.documents", "UNKNOWN", input, input))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> r.writing("sample.missing", "CHANGE", null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"admin-account", "role", "menu", "file", "cms.example", "cms", "plain"})
  void reservedOrUnqualifiedNamesFail(String resource) {
    assertThatThrownBy(() -> registry(contract(resource, 1, true, "enabled")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "password",
        "passwordHash",
        "SESSION_ID",
        "csrfToken",
        "jwt",
        "fcmToken",
        "providerId",
        "fileBody",
        "signedUrl",
        "sha256Hash"
      })
  void sensitiveRegistrationFails(String field) {
    assertThatThrownBy(() -> registry(contract("sample.documents", 1, true, field)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void duplicatesCurrentAndPermissionAreChecked() {
    var c = contract("sample.documents", 1, true, "enabled");
    assertThatThrownBy(() -> registry(c, c)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new HistoryContractRegistry(
                    List.of(() -> List.of(c), () -> List.of(c)), permissions))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registry(contract("sample.documents", 1, false, "enabled")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registry(c, contract("sample.documents", 2, true, "enabled")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registry(contract("sample.documents", 0, true, "enabled")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                registry(
                    new HistoryContract(
                        "sample.documents", 1, true, "missing", c.actions(), c.fields())))
        .isInstanceOf(AdminProblem.class);
  }

  @Test
  void oldVersionReadsAndUnknownVersionNeverReturnsRawPayload() {
    var r =
        registry(
            contract("sample.documents", 1, false, "enabled"),
            contract("sample.documents", 2, true, "enabled"));
    assertThat(
            r.writing(
                    "sample.documents", "CHANGE", Map.of("enabled", false), Map.of("enabled", true))
                .version())
        .isEqualTo(2);
    var old =
        new ChangeHistory.View(
            "id",
            "actor",
            "CHANGE",
            "sample.documents",
            "doc",
            Instant.EPOCH,
            Map.of("enabled", false),
            Map.of("enabled", true),
            1);
    assertThat(r.reading(old).payloadVersion()).isEqualTo(1);
    var invalid =
        new ChangeHistory.View(
            "id",
            "actor",
            "CHANGE",
            "sample.documents",
            "doc",
            Instant.EPOCH,
            null,
            Map.of("secret", "marker"),
            9);
    assertThatThrownBy(() -> r.reading(invalid))
        .isInstanceOfSatisfying(
            AdminProblem.class,
            p -> {
              assertThat(p.status()).isEqualTo(503);
              assertThat(p.code()).isEqualTo("HISTORY_CONTRACT_UNAVAILABLE");
              assertThat(p.getMessage()).doesNotContain("marker");
            });
  }

  @Test
  void scalarNullUuidTimeAndEnumBoundaries() {
    assertThat(
            new HistoryContract.Field(HistoryContract.Kind.UUID, true, 36, Set.of()).accepts(null))
        .isTrue();
    assertThat(
            new HistoryContract.Field(HistoryContract.Kind.UUID, false, 36, Set.of())
                .accepts("1-1-1-1-1"))
        .isFalse();
    var time = new HistoryContract.Field(HistoryContract.Kind.INSTANT, true, 32, Set.of());
    assertThat(time.accepts("2026-01-01T00:00:00.123Z")).isTrue();
    assertThat(time.accepts("2026-01-01T00:00:00.1234Z")).isFalse();
    assertThat(time.accepts("2026-01-01T00:00:00+09:00")).isFalse();
    assertThat(
            new HistoryContract.Field(HistoryContract.Kind.ENUM, false, 8, Set.of("READY"))
                .accepts("OTHER"))
        .isFalse();
  }
}
