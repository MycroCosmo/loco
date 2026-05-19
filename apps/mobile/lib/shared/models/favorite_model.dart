import 'map_model.dart';

class FavoriteModel {
  final String id;
  final String userId;
  final String mapId;
  final MapModel? map;
  final DateTime createdAt;

  const FavoriteModel({
    required this.id,
    required this.userId,
    required this.mapId,
    this.map,
    required this.createdAt,
  });

  factory FavoriteModel.fromJson(Map<String, dynamic> json) {
    return FavoriteModel(
      id: json['id'] as String,
      userId: json['userId'] as String,
      mapId: json['mapId'] as String,
      map: json['map'] != null
          ? MapModel.fromJson(json['map'] as Map<String, dynamic>)
          : null,
      createdAt: DateTime.parse(json['createdAt'] as String),
    );
  }
}
