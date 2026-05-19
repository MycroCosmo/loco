class KakaoPlaceResult {
  final String id;
  final String placeName;
  final String addressName;
  final String roadAddressName;
  final String phone;
  final String categoryName;
  final double latitude;
  final double longitude;
  final String placeUrl;

  const KakaoPlaceResult({
    required this.id,
    required this.placeName,
    required this.addressName,
    required this.roadAddressName,
    required this.phone,
    required this.categoryName,
    required this.latitude,
    required this.longitude,
    required this.placeUrl,
  });

  factory KakaoPlaceResult.fromJson(Map<String, dynamic> json) {
    return KakaoPlaceResult(
      id: (json['id'] as String?) ?? '',
      placeName: (json['placeName'] as String?) ?? '',
      addressName: (json['addressName'] as String?) ?? '',
      roadAddressName: (json['roadAddressName'] as String?) ?? '',
      phone: (json['phone'] as String?) ?? '',
      categoryName: (json['categoryName'] as String?) ?? '',
      latitude: (json['latitude'] as num).toDouble(),
      longitude: (json['longitude'] as num).toDouble(),
      placeUrl: (json['placeUrl'] as String?) ?? '',
    );
  }
}
