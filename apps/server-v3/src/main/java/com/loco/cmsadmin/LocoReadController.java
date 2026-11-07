package com.loco.cmsadmin;

import com.cms.core.account.AdminIdentity;
import com.cms.core.web.CmsController;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@CmsController
@RequestMapping("/admin/api/loco")
public class LocoReadController {
  private final LocoReadService reads;

  public LocoReadController(LocoReadService reads) {
    this.reads = reads;
  }

  @GetMapping("/{resource}")
  public LocoReadService.Page list(
      @AuthenticationPrincipal AdminIdentity actor,
      @PathVariable String resource,
      @RequestParam MultiValueMap<String, String> query) {
    return reads.list(actor, LocoReadResource.path(resource), query);
  }

  @GetMapping("/{resource}/{id}")
  public Map<String, Object> detail(
      @AuthenticationPrincipal AdminIdentity actor,
      @PathVariable String resource,
      @PathVariable String id) {
    return reads.detail(actor, LocoReadResource.path(resource), id);
  }
}
