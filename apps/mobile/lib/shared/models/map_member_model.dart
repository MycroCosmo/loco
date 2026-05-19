class MapMemberModel {
  final String id;
  final String nickname;
  final String? profileImageUrl;
  final DateTime joinedAt;
  final bool isOwner;

  const MapMemberModel({
    required this.id,
    required this.nickname,
    this.profileImageUrl,
    required this.joinedAt,
    required this.isOwner,
  });

  factory MapMemberModel.fromJson(Map<String, dynamic> json) {
    return MapMemberModel(
      id: json['id'] as String,
      nickname: json['nickname'] as String,
      profileImageUrl: json['profileImageUrl'] as String?,
      joinedAt: DateTime.parse(json['joinedAt'] as String),
      isOwner: json['isOwner'] as bool? ?? false,
    );
  }
}
