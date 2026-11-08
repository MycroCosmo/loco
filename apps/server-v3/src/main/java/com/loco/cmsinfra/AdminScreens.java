package com.loco.cmsinfra;

import com.cms.core.permission.PermissionCatalog;
import java.util.*;
import org.springframework.context.annotation.*;

/** Assembly-owned route keys; the reusable core has no knowledge of UI pages. */
@Configuration(proxyBeanMethods = false)
public class AdminScreens {
  public static final List<String> KEYS =
      List.of(
          "cms.accounts",
          "cms.roles",
          "cms.menus",
          "cms.files",
          "loco.users",
          "loco.maps",
          "loco.places",
          "loco.reports");

  @Bean
  PermissionCatalog.Contribution adminScreenContribution() {
    return new PermissionCatalog.Contribution() {
      public Collection<PermissionCatalog.Definition> permissions() {
        return Arrays.stream(com.loco.cmsadmin.LocoReadResource.values())
            .map(
                resource ->
                    new PermissionCatalog.Definition(
                        resource.key(),
                        resource.label(),
                        resource == com.loco.cmsadmin.LocoReadResource.REPORTS
                            ? Set.of(
                                com.cms.core.permission.Action.READ,
                                com.cms.core.permission.Action.MANAGE)
                            : Set.of(com.cms.core.permission.Action.READ),
                        false))
            .toList();
      }

      public Collection<PermissionCatalog.Screen> screens() {
        return KEYS.stream().map(key -> new PermissionCatalog.Screen(key, key)).toList();
      }
    };
  }
}
