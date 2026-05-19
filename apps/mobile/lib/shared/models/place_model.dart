class PlaceModel {
  final String id;
  final String mapId;
  final String createdById;
  final String name;
  final String address;
  final String? roadAddress;
  final double latitude;
  final double longitude;
  final String? phone;
  final String? category;
  final String? memo;
  final List<String> tags;
  final List<String> imageUrls;
  final double averageRating;
  final int reviewCount;
  final DateTime createdAt;
  final PlaceAuthorModel? createdBy;
  final PlaceMapSummaryModel? map;

  const PlaceModel({
    required this.id,
    required this.mapId,
    required this.createdById,
    required this.name,
    required this.address,
    this.roadAddress,
    required this.latitude,
    required this.longitude,
    this.phone,
    this.category,
    this.memo,
    this.tags = const [],
    this.imageUrls = const [],
    this.averageRating = 0,
    this.reviewCount = 0,
    required this.createdAt,
    this.createdBy,
    this.map,
  });

  factory PlaceModel.fromJson(Map<String, dynamic> json) {
    return PlaceModel(
      id: json['id'] as String,
      mapId: json['mapId'] as String,
      createdById: json['createdById'] as String,
      name: json['name'] as String,
      address: json['address'] as String,
      roadAddress: json['roadAddress'] as String?,
      latitude: (json['latitude'] as num).toDouble(),
      longitude: (json['longitude'] as num).toDouble(),
      phone: json['phone'] as String?,
      category: json['category'] as String?,
      memo: json['memo'] as String?,
      tags: List<String>.from(json['tags'] as List? ?? []),
      imageUrls: List<String>.from(json['imageUrls'] as List? ?? []),
      averageRating: (json['averageRating'] as num? ?? 0).toDouble(),
      reviewCount: json['reviewCount'] as int? ?? 0,
      createdAt: DateTime.parse(json['createdAt'] as String),
      createdBy: json['createdBy'] == null
          ? null
          : PlaceAuthorModel.fromJson(json['createdBy'] as Map<String, dynamic>),
      map: json['map'] == null
          ? null
          : PlaceMapSummaryModel.fromJson(json['map'] as Map<String, dynamic>),
    );
  }

  Map<String, dynamic> toJson() => {
        'name': name,
        'address': address,
        'roadAddress': roadAddress,
        'latitude': latitude,
        'longitude': longitude,
        'phone': phone,
        'category': category,
        'memo': memo,
        'tags': tags,
      };
}

class PlaceMapSummaryModel {
  final String id;
  final String title;

  const PlaceMapSummaryModel({
    required this.id,
    required this.title,
  });

  factory PlaceMapSummaryModel.fromJson(Map<String, dynamic> json) {
    return PlaceMapSummaryModel(
      id: json['id'] as String,
      title: json['title'] as String,
    );
  }
}

class PlaceAuthorModel {
  final String id;
  final String nickname;
  final String? profileImageUrl;

  const PlaceAuthorModel({
    required this.id,
    required this.nickname,
    this.profileImageUrl,
  });

  factory PlaceAuthorModel.fromJson(Map<String, dynamic> json) {
    return PlaceAuthorModel(
      id: json['id'] as String,
      nickname: json['nickname'] as String,
      profileImageUrl: json['profileImageUrl'] as String?,
    );
  }
}
