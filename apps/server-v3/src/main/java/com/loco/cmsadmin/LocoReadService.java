package com.loco.cmsadmin;

import com.cms.core.account.*;
import com.cms.core.permission.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

@Service
@Transactional(readOnly = true)
public class LocoReadService {
  public record Page(List<Map<String, Object>> items, String nextCursor) {}

  private final PermissionService access;
  private final LocoReadRepository reads;

  public LocoReadService(PermissionService access, LocoReadRepository reads) {
    this.access = access;
    this.reads = reads;
  }

  public Page list(
      AdminIdentity actor, LocoReadResource resource, MultiValueMap<String, String> input) {
    access.require(actor, resource.key(), Action.READ);
    var filter = LocoReadFilter.parse(resource, input);
    var rows = reads.read(reads.listPlan(resource, filter));
    var items = rows.stream().limit(filter.size()).toList();
    return new Page(items, rows.size() > filter.size() ? filter.next(items.getLast()) : null);
  }

  public Map<String, Object> detail(AdminIdentity actor, LocoReadResource resource, String id) {
    access.require(actor, resource.key(), Action.READ);
    var rows = reads.read(reads.detailPlan(resource, LocoReadFilter.uuid(id)));
    if (rows.isEmpty()) throw new AdminProblem(404, "Not found");
    return rows.getFirst();
  }
}
