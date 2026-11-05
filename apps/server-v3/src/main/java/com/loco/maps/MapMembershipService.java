package com.loco.maps;

import com.loco.notifications.MemberJoinedNotification;
import com.loco.notifications.NotificationSender;
import com.loco.users.UserRepository;
import com.loco.users.UserResponses;
import com.loco.web.ApiProblem;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MapMembershipService {
  private final MapRepository maps;
  private final MapMemberRepository members;
  private final MapQueryRepository queries;
  private final MapPermissions permissions;
  private final UserRepository users;
  private final Clock clock;
  private final ApplicationEventPublisher events;

  public MapMembershipService(
      MapRepository maps,
      MapMemberRepository members,
      MapQueryRepository queries,
      MapPermissions permissions,
      UserRepository users,
      Clock clock,
      ApplicationEventPublisher events) {
    this.maps = maps;
    this.members = members;
    this.queries = queries;
    this.permissions = permissions;
    this.users = users;
    this.clock = clock;
    this.events = events;
  }

  @Transactional(readOnly = true)
  public boolean membership(String id, String userId) {
    var room = room(id);
    if (room.getOwner().getId().equals(userId)) return true;
    if (room.getVisibility() == Visibility.PRIVATE) {
      permissions.requireReadable(id, room.getOwner().getId(), room.getVisibility().name(), userId);
      return true;
    }
    return members.existsByMap_IdAndUser_Id(id, userId);
  }

  @Transactional
  public void join(String id, String userId) {
    var room = room(id);
    if (room.getVisibility() != Visibility.PUBLIC)
      throw ApiProblem.forbidden("비공개 지도방에는 가입할 수 없습니다.");
    addMember(room, userId);
  }

  @Transactional
  public MapResponses.Detail joinByCode(String code, String userId) {
    String normalized = code.strip().toUpperCase(java.util.Locale.ROOT);
    if (normalized.isEmpty()) throw ApiProblem.badRequest("초대 코드를 입력해주세요.");
    var room =
        maps.findByInviteCode(normalized)
            .filter(map -> map.getVisibility() == Visibility.PRIVATE)
            .orElseThrow(() -> ApiProblem.notFound("유효한 초대 코드를 찾을 수 없습니다."));
    addMember(room, userId);
    return queries.detail(room.getId()).orElseThrow();
  }

  private void addMember(MapRoom room, String userId) {
    members.join(UUID.randomUUID().toString(), room.getId(), userId, LocalDateTime.now(clock));
    String nickname = users.findById(userId).map(user -> user.getNickname()).orElse("누군가");
    events.publishEvent(
        new MemberJoinedNotification(
            room.getOwner().getId(),
            new NotificationSender.Message(
                "새 멤버 가입",
                nickname + "님이 \"" + room.getTitle() + "\"에 가입했습니다.",
                Map.of("type", "member_joined", "mapId", room.getId()))));
  }

  @Transactional
  public void leave(String id, String userId) {
    var room = room(id);
    if (room.getOwner().getId().equals(userId))
      throw ApiProblem.forbidden("내가 만든 지도방에서는 나갈 수 없습니다.");
    members.leave(id, userId);
  }

  @Transactional
  public void remove(String id, String requesterId, String targetId) {
    var room = room(id);
    if (!room.getOwner().getId().equals(requesterId))
      throw ApiProblem.forbidden("방장만 멤버를 내보낼 수 있습니다.");
    if (targetId.equals(requesterId)) throw ApiProblem.badRequest("자기 자신을 내보낼 수 없습니다.");
    if (targetId.equals(room.getOwner().getId())) throw ApiProblem.badRequest("방장은 내보낼 수 없습니다.");
    members.leave(id, targetId);
  }

  public record Member(
      String id, String nickname, String profileImageUrl, String joinedAt, boolean isOwner) {}

  @Transactional(readOnly = true)
  public List<Member> members(String id, String userId) {
    var room = room(id);
    String ownerId = room.getOwner().getId();
    if (!ownerId.equals(userId) && !members.existsByMap_IdAndUser_Id(id, userId))
      throw ApiProblem.forbidden("멤버 목록을 볼 권한이 없습니다.");
    return members.findByMap_IdOrderByCreatedAtAsc(id).stream()
        .map(
            member ->
                new Member(
                    member.getUser().getId(),
                    member.getUser().getNickname(),
                    member.getUser().getProfileImageUrl(),
                    UserResponses.timestamp(member.getCreatedAt()),
                    member.getUser().getId().equals(ownerId)))
        .toList();
  }

  private MapRoom room(String id) {
    return maps.findById(id).orElseThrow(() -> ApiProblem.notFound("지도방을 찾을 수 없습니다."));
  }
}
