package com.cms.core.account;

import com.cms.core.CmsCoreConfiguration;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.*;

/** One-shot process: password arguments are rejected before starting Spring. */
public final class AdminBootstrap {
  private AdminBootstrap() {}

  public static void main(String[] args) {
    System.exit(run(args, System.in, System.out, System.err));
  }

  public static int run(String[] args, InputStream input, PrintStream output, PrintStream error) {
    try {
      Map<String, String> options = new HashMap<>();
      for (String arg : args) {
        int equals = arg.indexOf('=');
        if (equals < 0 || !(arg.startsWith("--login-id=") || arg.startsWith("--display-name=")))
          throw new IllegalArgumentException();
        if (options.put(arg.substring(0, equals), arg.substring(equals + 1)) != null)
          throw new IllegalArgumentException();
      }
      String login = options.get("--login-id"), display = options.get("--display-name");
      if (login == null || login.isBlank() || display == null || display.isBlank())
        throw new IllegalArgumentException();
      String password = System.getenv("CMS_BOOTSTRAP_PASSWORD");
      if (password == null) {
        if (System.console() != null) {
          char[] chars = System.console().readPassword("Initial administrator password: ");
          if (chars != null) {
            password = new String(chars);
            Arrays.fill(chars, '\0');
          }
        } else
          password =
              new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)).readLine();
      }
      if (password == null || password.isBlank()) throw new IllegalArgumentException();
      var app = new SpringApplication(Host.class);
      app.setWebApplicationType(WebApplicationType.NONE);
      app.setBannerMode(Banner.Mode.OFF);
      app.setLogStartupInfo(false);
      // Fixed properties prevent datasource/SQL debug logs from exposing secret input.
      try (var context =
          app.run(
              "--logging.level.root=OFF",
              "--spring.jpa.show-sql=false",
              "--logging.level.org.hibernate.SQL=OFF",
              "--logging.level.org.hibernate.orm.jdbc.bind=OFF",
              "--spring.flyway.locations=classpath:db/migration,classpath:db/cms",
              "--spring.flyway.default-schema=public",
              "--spring.flyway.baseline-on-migrate=false",
              "--spring.datasource.url=${DB_URL}",
              "--spring.datasource.username=${DB_USERNAME}",
              "--spring.datasource.password=${DB_PASSWORD}")) {
        context.getBean(AdminService.class).bootstrap(login, display, password);
      }
      output.println("Initial administrator created; password change required.");
      return 0;
    } catch (Exception exception) {
      error.println(
          "Administrator bootstrap failed. Check input, database access and whether bootstrap was already completed.");
      return 1;
    }
  }

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import(CmsCoreConfiguration.class)
  public static class Host {}
}
