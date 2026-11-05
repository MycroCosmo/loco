package com.loco.maps;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** 실제 HTTP 요청의 인증 조회와 응답 직렬화를 포함한다. 테스트는 병렬 실행하지 않는다. */
public final class SqlQueryCounter implements StatementInspector {
  private static final List<String> SQL = new CopyOnWriteArrayList<>();

  @Override
  public String inspect(String sql) {
    SQL.add(sql);
    return sql;
  }

  static void reset() {
    SQL.clear();
  }

  static List<String> statements() {
    return List.copyOf(SQL);
  }

  @TestConfiguration
  public static class Configuration {
    @Bean
    HibernatePropertiesCustomizer countQueries() {
      return properties ->
          properties.put("hibernate.session_factory.statement_inspector", new SqlQueryCounter());
    }
  }
}
