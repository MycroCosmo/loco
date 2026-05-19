import 'place_model.dart';

class PlaceReviewsSummary {
  final double averageRating;
  final int reviewCount;
  final List<PlaceReviewModel> reviews;

  const PlaceReviewsSummary({
    required this.averageRating,
    required this.reviewCount,
    required this.reviews,
  });

  factory PlaceReviewsSummary.fromJson(Map<String, dynamic> json) {
    final reviews = json['reviews'] as List? ?? [];
    return PlaceReviewsSummary(
      averageRating: (json['averageRating'] as num? ?? 0).toDouble(),
      reviewCount: json['reviewCount'] as int? ?? reviews.length,
      reviews: reviews
          .map((item) => PlaceReviewModel.fromJson(item as Map<String, dynamic>))
          .toList(),
    );
  }
}

class PlaceReviewModel {
  final String id;
  final String placeId;
  final String userId;
  final int rating;
  final String content;
  final DateTime createdAt;
  final DateTime updatedAt;
  final ReviewUserModel? user;
  final PlaceModel? place;

  const PlaceReviewModel({
    required this.id,
    required this.placeId,
    required this.userId,
    required this.rating,
    required this.content,
    required this.createdAt,
    required this.updatedAt,
    this.user,
    this.place,
  });

  factory PlaceReviewModel.fromJson(Map<String, dynamic> json) {
    return PlaceReviewModel(
      id: json['id'] as String,
      placeId: json['placeId'] as String,
      userId: json['userId'] as String,
      rating: json['rating'] as int,
      content: json['content'] as String,
      createdAt: DateTime.parse(json['createdAt'] as String),
      updatedAt: DateTime.parse(json['updatedAt'] as String),
      user: json['user'] == null
          ? null
          : ReviewUserModel.fromJson(json['user'] as Map<String, dynamic>),
      place: json['place'] == null
          ? null
          : PlaceModel.fromJson(json['place'] as Map<String, dynamic>),
    );
  }
}

class ReviewUserModel {
  final String id;
  final String nickname;
  final String? profileImageUrl;

  const ReviewUserModel({
    required this.id,
    required this.nickname,
    this.profileImageUrl,
  });

  factory ReviewUserModel.fromJson(Map<String, dynamic> json) {
    return ReviewUserModel(
      id: json['id'] as String,
      nickname: json['nickname'] as String,
      profileImageUrl: json['profileImageUrl'] as String?,
    );
  }
}
