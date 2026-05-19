import 'place_model.dart';

class MarkerClusterPlacesResult {
  final int count;
  final List<PlaceModel> items;
  final bool hasMore;

  const MarkerClusterPlacesResult({
    required this.count,
    required this.items,
    required this.hasMore,
  });

  factory MarkerClusterPlacesResult.fromJson(Map<String, dynamic> json) {
    final items = json['items'] as List? ?? const [];
    return MarkerClusterPlacesResult(
      count: json['count'] as int? ?? items.length,
      items: items
          .map((item) => PlaceModel.fromJson(item as Map<String, dynamic>))
          .toList(),
      hasMore: json['hasMore'] as bool? ?? false,
    );
  }
}
