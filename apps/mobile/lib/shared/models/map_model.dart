class MapModel {
  final String id;
  final String ownerId;
  final String title;
  final String? description;
  final List<String> tags;
  final String? coverImageUrl;
  final String visibility;
  final String? inviteCode;
  final int placesCount;
  final int favoritesCount;
  final int membersCount;
  final DateTime createdAt;

  const MapModel({
    required this.id,
    required this.ownerId,
    required this.title,
    this.description,
    this.tags = const [],
    this.coverImageUrl,
    required this.visibility,
    this.inviteCode,
    this.placesCount = 0,
    this.favoritesCount = 0,
    this.membersCount = 0,
    required this.createdAt,
  });

  bool get isPublic => visibility == 'PUBLIC';

  factory MapModel.fromJson(Map<String, dynamic> json) {
    final count = json['_count'] as Map<String, dynamic>?;
    return MapModel(
      id: json['id'] as String,
      ownerId: json['ownerId'] as String,
      title: json['title'] as String,
      description: json['description'] as String?,
      tags: List<String>.from(json['tags'] as List? ?? []),
      coverImageUrl: json['coverImageUrl'] as String?,
      visibility: json['visibility'] as String,
      inviteCode: json['inviteCode'] as String?,
      placesCount: count?['places'] as int? ?? 0,
      favoritesCount: count?['favorites'] as int? ?? 0,
      membersCount: count?['members'] as int? ?? 0,
      createdAt: DateTime.parse(json['createdAt'] as String),
    );
  }

  Map<String, dynamic> toJson() => {
        'id': id,
        'ownerId': ownerId,
        'title': title,
        'description': description,
        'tags': tags,
        'coverImageUrl': coverImageUrl,
        'visibility': visibility,
        'inviteCode': inviteCode,
        'membersCount': membersCount,
        'createdAt': createdAt.toIso8601String(),
      };
}
