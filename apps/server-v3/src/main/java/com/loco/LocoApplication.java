package com.loco;

import com.cms.core.CmsCoreConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@Import(CmsCoreConfiguration.class)
@EntityScan(basePackageClasses = LocoApplication.class)
@EnableJpaRepositories(basePackageClasses = LocoApplication.class)
public class LocoApplication {

  public static void main(String[] args) {
    if (args.length > 0 && args[0].equals("cms-bootstrap")) {
      com.cms.core.account.AdminBootstrap.main(java.util.Arrays.copyOfRange(args, 1, args.length));
      return;
    }
    SpringApplication.run(LocoApplication.class, args);
  }
}
