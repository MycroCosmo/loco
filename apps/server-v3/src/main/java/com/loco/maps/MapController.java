package com.loco.maps;

import com.loco.security.AuthenticatedApi;
import com.loco.security.JwtPrincipal;
import com.loco.web.ApiController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@ApiController @AuthenticatedApi
public class MapController {
    private final MapService maps;
    private final MapQueryRepository queries;
    public MapController(MapService maps,MapQueryRepository queries) { this.maps=maps; this.queries=queries; }
    @GetMapping("/maps")
    public MapResponses.Page list(@AuthenticationPrincipal JwtPrincipal user,
            @RequestParam(required=false) String cursor,@RequestParam(required=false) String limit,
            @RequestParam(required=false) String visibility,@RequestParam(required=false) String sort,
            @RequestParam(required=false) String search) {
        return queries.list(user.userId(),MapInputs.listing(cursor,limit,visibility,sort,search));
    }
    @GetMapping("/maps/me")
    public java.util.List<MapResponses.Detail> mine(@AuthenticationPrincipal JwtPrincipal user,@RequestParam(required=false) String type) {
        return queries.mine(user.userId(),type);
    }
    @GetMapping("/maps/{id}")
    public MapResponses.Detail detail(@AuthenticationPrincipal JwtPrincipal user,@PathVariable String id) { return maps.detail(id,user.userId()); }
}
