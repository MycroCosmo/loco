import 'place_model.dart';

enum MapMarkerType {
  place,
  group,
  cluster;

  static MapMarkerType fromJson(String? value) {
    return switch (value) {
      'group' => MapMarkerType.group,
      'cluster' => MapMarkerType.cluster,
      _ => MapMarkerType.place,
    };
  }
}

class MapMarkerModel {
  final String id;
  final String name;
  final double latitude;
  final double longitude;
  final MapMarkerType type;
  final int count;
  final String? groupKey;
  final String? clusterKey;
  final int? precision;
  final String? cellLatKey;
  final String? cellLngKey;

  const MapMarkerModel({
    required this.id,
    required this.name,
    required this.latitude,
    required this.longitude,
    this.type = MapMarkerType.place,
    this.count = 1,
    this.groupKey,
    this.clusterKey,
    this.precision,
    this.cellLatKey,
    this.cellLngKey,
  });

  factory MapMarkerModel.fromJson(Map<String, dynamic> json) {
    return MapMarkerModel(
      id: json['id'] as String,
      name: json['name'] as String,
      latitude: (json['latitude'] as num).toDouble(),
      longitude: (json['longitude'] as num).toDouble(),
      type: MapMarkerType.fromJson(json['type'] as String?),
      count: json['count'] as int? ?? 1,
      groupKey: json['groupKey'] as String?,
      clusterKey: json['clusterKey'] as String?,
      precision: json['precision'] as int?,
      cellLatKey: json['cellLatKey'] as String?,
      cellLngKey: json['cellLngKey'] as String?,
    );
  }

  factory MapMarkerModel.fromPlace(PlaceModel place) {
    return MapMarkerModel(
      id: place.id,
      name: place.name,
      latitude: place.latitude,
      longitude: place.longitude,
    );
  }

  bool get isPlace => type == MapMarkerType.place;
  bool get isGroup => type == MapMarkerType.group;
  bool get isCluster => type == MapMarkerType.cluster;
  bool get isOverflowCluster => clusterKey == 'overflow';
}
