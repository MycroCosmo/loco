package com.loco.maps;

import com.loco.web.ApiProblem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MapService {
  private final MapQueryRepository queries;
  private final MapPermissions permissions;

  public MapService(MapQueryRepository queries, MapPermissions permissions) {
    this.queries = queries;
    this.permissions = permissions;
  }

  @Transactional(readOnly = true)
  public MapResponses.Detail detail(String id, String userId) {
    var row = queries.detail(id).orElseThrow(() -> ApiProblem.notFound("지도방을 찾을 수 없습니다."));
    permissions.requireReadable(id, row.map().ownerId(), row.map().visibility(), userId);
    return row;
  }
}
