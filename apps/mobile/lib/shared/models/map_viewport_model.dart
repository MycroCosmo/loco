class MapViewportModel {
  final double swLat;
  final double swLng;
  final double neLat;
  final double neLng;
  final double centerLat;
  final double centerLng;
  final int level;

  const MapViewportModel({
    required this.swLat,
    required this.swLng,
    required this.neLat,
    required this.neLng,
    required this.centerLat,
    required this.centerLng,
    required this.level,
  });

  factory MapViewportModel.fromJson(Map<String, dynamic> json) {
    return MapViewportModel(
      swLat: (json['swLat'] as num).toDouble(),
      swLng: (json['swLng'] as num).toDouble(),
      neLat: (json['neLat'] as num).toDouble(),
      neLng: (json['neLng'] as num).toDouble(),
      centerLat: (json['centerLat'] as num).toDouble(),
      centerLng: (json['centerLng'] as num).toDouble(),
      level: json['level'] as int,
    );
  }
}
