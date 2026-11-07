package com.loco.cmsadmin;

import com.cms.core.account.AdminProblem;
import java.util.*;

public enum LocoReadResource {
  USERS(
      "users",
      "회원 조회",
      "id,nickname,email,provider,deletedAt,createdAt,updatedAt",
      "",
      Set.of("id", "search", "provider", "status")),
  MAPS(
      "maps",
      "지도방 조회 (비공개 포함)",
      "id,title,ownerId,visibility,tags,createdAt,updatedAt",
      "description",
      Set.of("id", "ownerId", "search", "visibility", "tag")),
  PLACES(
      "places",
      "장소 조회 (비공개 포함)",
      "id,name,mapId,createdById,createdAt,updatedAt",
      "address,roadAddress,latitude,longitude,category,memo,tags",
      Set.of("id", "mapId", "createdById", "search", "tag"));

  final String table;
  final String label;
  final String columns;
  final String detailColumns;
  final Set<String> filters;

  LocoReadResource(
      String table, String label, String columns, String detailColumns, Set<String> filters) {
    this.table = table;
    this.label = label;
    this.columns = columns;
    this.detailColumns = detailColumns;
    this.filters = filters;
  }

  public String key() {
    return "loco." + table;
  }

  public String label() {
    return label;
  }

  public static LocoReadResource path(String path) {
    return Arrays.stream(values())
        .filter(r -> r.table.equals(path))
        .findFirst()
        .orElseThrow(() -> new AdminProblem(404, "Not found"));
  }
}
