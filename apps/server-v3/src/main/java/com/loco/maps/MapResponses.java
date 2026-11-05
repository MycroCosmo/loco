package com.loco.maps;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.loco.users.UserResponses;
import jakarta.persistence.Tuple;

public final class MapResponses {
    private MapResponses() { }
    public record Core(String id,String ownerId,String title,String description,String[] tags,String coverImageUrl,
                       String visibility,String inviteCode,String createdAt,String updatedAt) { }
    public record Owner(String id,String nickname,String profileImageUrl) { }
    public record Counts(long places,long favorites,long members) { }
    public record Detail(@JsonUnwrapped Core map, Owner owner,@JsonProperty("_count") Counts count) { }
    public record Page(List<Detail> items,String nextCursor,boolean hasMore) { }
    public static Core core(MapRoom room) {
        return new Core(room.getId(),room.getOwner().getId(),room.getTitle(),room.getDescription(),room.getTags(),
                room.getCoverImageUrl(),room.getVisibility().name(),room.getInviteCode(),
                UserResponses.timestamp(room.getCreatedAt()),UserResponses.timestamp(room.getUpdatedAt()));
    }
    static Detail detail(Tuple row) {
        var core = new Core(text(row,"id"),text(row,"ownerId"),text(row,"title"),text(row,"description"),
                (String[])row.get("tags"),text(row,"coverImageUrl"),text(row,"visibility"),text(row,"inviteCode"),
                timestamp(row.get("createdAt")),timestamp(row.get("updatedAt")));
        return new Detail(core,new Owner(core.ownerId(),text(row,"ownerNickname"),text(row,"ownerImage")),
                new Counts(number(row,"placeCount"),number(row,"favoriteCount"),number(row,"memberCount")));
    }
    private static String text(Tuple row,String name) { Object value=row.get(name); return value==null?null:value.toString(); }
    private static long number(Tuple row,String name) { return ((Number)row.get(name)).longValue(); }
    private static String timestamp(Object value) {
        return UserResponses.timestamp(value instanceof java.sql.Timestamp t?t.toLocalDateTime():(java.time.LocalDateTime)value);
    }
}
