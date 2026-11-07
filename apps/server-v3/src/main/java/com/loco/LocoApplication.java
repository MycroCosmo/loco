package com.loco;

import com.cms.core.CmsCoreConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(CmsCoreConfiguration.class)
public class LocoApplication {

  public static void main(String[] args) {
    SpringApplication.run(LocoApplication.class, args);
  }
}
