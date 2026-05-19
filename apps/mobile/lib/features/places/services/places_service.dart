import 'package:dio/dio.dart';
import 'package:image_picker/image_picker.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/constants/api_constants.dart';
import '../../../core/utils/image_utils.dart';
import '../../../shared/models/map_marker_model.dart';
import '../../../shared/models/map_viewport_model.dart';
import '../../../shared/models/marker_cluster_places_result.dart';
import '../../../shared/models/place_model.dart';
import '../../../shared/models/place_review_model.dart';
import '../../../shared/models/paged_result.dart';

class PlacesService {
  final _client = DioClient();

  Future<PagedResult<PlaceModel>> getPlaces(
    String mapId, {
    String? cursor,
    int limit = 20,
    String sort = 'latest',
    String? search,
    String? tag,
  }) async {
    final response = await _client.dio.get(
      ApiConstants.mapPlaces(mapId),
      queryParameters: {
        'limit': limit,
        'sort': sort,
        if (cursor != null) 'cursor': cursor,
        if (search != null && search.trim().isNotEmpty)
          'search': search.trim(),
        if (tag != null && tag.trim().isNotEmpty) 'tag': tag.trim(),
      },
    );
    return PagedResult.fromJson(
      response.data as Map<String, dynamic>,
      PlaceModel.fromJson,
    );
  }

  Future<List<MapMarkerModel>> getPlaceMarkers(
    String mapId,
    MapViewportModel viewport,
  ) async {
    final response = await _client.dio.get(
      '${ApiConstants.mapPlaces(mapId)}/markers',
      queryParameters: {
        'swLat': viewport.swLat,
        'swLng': viewport.swLng,
        'neLat': viewport.neLat,
        'neLng': viewport.neLng,
        'level': viewport.level,
      },
    );
    final list = response.data as List;
    return list
        .map((item) => MapMarkerModel.fromJson(item as Map<String, dynamic>))
        .toList();
  }

  Future<List<PlaceModel>> getMarkerGroupPlaces(
    String mapId, {
    required double latitude,
    required double longitude,
    String? groupKey,
  }) async {
    final response = await _client.dio.get(
      '${ApiConstants.mapPlaces(mapId)}/marker-group',
      queryParameters: {
        'lat': latitude,
        'lng': longitude,
        if (groupKey != null) 'groupKey': groupKey,
      },
    );
    final list = response.data as List;
    return list
        .map((item) => PlaceModel.fromJson(item as Map<String, dynamic>))
        .toList();
  }

  Future<MarkerClusterPlacesResult> getMarkerClusterPlaces(
    String mapId, {
    required int precision,
    required String cellLatKey,
    required String cellLngKey,
    int limit = 50,
  }) async {
    final response = await _client.dio.get(
      '${ApiConstants.mapPlaces(mapId)}/marker-cluster',
      queryParameters: {
        'precision': precision,
        'cellLatKey': cellLatKey,
        'cellLngKey': cellLngKey,
        'limit': limit,
      },
    );
    return MarkerClusterPlacesResult.fromJson(
      response.data as Map<String, dynamic>,
    );
  }

  Future<PlaceModel> getPlace(String placeId) async {
    final response = await _client.dio.get(ApiConstants.placeDetail(placeId));
    return PlaceModel.fromJson(response.data as Map<String, dynamic>);
  }

  Future<PlaceModel> createPlace(String mapId, PlaceModel place) async {
    final response = await _client.dio.post(
      ApiConstants.mapPlaces(mapId),
      data: place.toJson(),
    );
    return PlaceModel.fromJson(response.data as Map<String, dynamic>);
  }

  Future<List<PlaceModel>> getMyPlaces() async {
    final response = await _client.dio.get('/places/me');
    final list = response.data as List;
    return list
        .map((item) => PlaceModel.fromJson(item as Map<String, dynamic>))
        .toList();
  }

  Future<List<PlaceReviewModel>> getMyReviews() async {
    final response = await _client.dio.get('/places/reviews/me');
    final list = response.data as List;
    return list
        .map((item) => PlaceReviewModel.fromJson(item as Map<String, dynamic>))
        .toList();
  }

  Future<PlaceModel> updatePlace(String placeId, Map<String, dynamic> data) async {
    final response =
        await _client.dio.put(ApiConstants.placeDetail(placeId), data: data);
    return PlaceModel.fromJson(response.data as Map<String, dynamic>);
  }

  Future<void> deletePlace(String placeId) async {
    await _client.dio.delete(ApiConstants.placeDetail(placeId));
  }

  Future<void> reportPlace(
    String placeId, {
    required String reason,
    String? detail,
  }) async {
    await _client.dio.post(
      ApiConstants.reports,
      data: {
        'targetType': 'PLACE',
        'targetId': placeId,
        'reason': reason,
        'detail': detail,
      },
    );
  }

  Future<PlaceReviewsSummary> getPlaceReviews(String placeId) async {
    final response = await _client.dio.get('/places/$placeId/reviews');
    return PlaceReviewsSummary.fromJson(response.data as Map<String, dynamic>);
  }

  Future<PlaceReviewsSummary> submitPlaceReview(
    String placeId, {
    required int rating,
    required String content,
  }) async {
    final response = await _client.dio.post(
      '/places/$placeId/reviews',
      data: {
        'rating': rating,
        'content': content,
      },
    );
    return PlaceReviewsSummary.fromJson(response.data as Map<String, dynamic>);
  }

  Future<PlaceReviewsSummary> deleteMyPlaceReview(String placeId) async {
    final response = await _client.dio.delete('/places/$placeId/reviews/me');
    return PlaceReviewsSummary.fromJson(response.data as Map<String, dynamic>);
  }

  Future<PlaceModel> uploadPlaceImage(String placeId, XFile imageFile) async {
    final bytes = await imageFile.readAsBytes();
    final formData = FormData.fromMap({
      'file': MultipartFile.fromBytes(
        bytes,
        filename: imageFile.name.isNotEmpty ? imageFile.name : 'photo.jpg',
        contentType: imageContentType(imageFile),
      ),
    });
    final response = await _client.dio.post('/places/$placeId/images', data: formData);
    return PlaceModel.fromJson(response.data as Map<String, dynamic>);
  }

  Future<PlaceModel> deletePlaceImage(String placeId, String imageUrl) async {
    final response = await _client.dio.delete(
      '/places/$placeId/images',
      data: {'imageUrl': imageUrl},
    );
    return PlaceModel.fromJson(response.data as Map<String, dynamic>);
  }
}
