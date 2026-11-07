package com.loco.cmsinfra;

import com.cms.core.permission.PermissionCatalog;
import java.util.*;
import org.springframework.context.annotation.*;

/** Assembly-owned route keys; the reusable core has no knowledge of UI pages. */
@Configuration(proxyBeanMethods = false)
public class AdminScreens {
  public static final List<String> KEYS =
      List.of("cms.accounts", "cms.roles", "cms.menus", "cms.files");

  @Bean
  PermissionCatalog.Contribution adminScreens() {
    return new PermissionCatalog.Contribution() {
      public Collection<PermissionCatalog.Definition> permissions() {
        return List.of();
      }

      public Collection<PermissionCatalog.Screen> screens() {
        return KEYS.stream().map(key -> new PermissionCatalog.Screen(key, key)).toList();
      }
    };
  }
}
