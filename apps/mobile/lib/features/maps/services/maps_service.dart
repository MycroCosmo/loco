import 'package:dio/dio.dart';
import 'package:image_picker/image_picker.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/constants/api_constants.dart';
import '../../../core/utils/image_utils.dart';
import '../../../shared/models/map_model.dart';
import '../../../shared/models/map_member_model.dart';
import '../../../shared/models/paged_result.dart';

class MapsService {
  final _client = DioClient();

  Future<PagedResult<MapModel>> getMaps({
    String? cursor,
    int limit = 20,
    String? visibility,
    String sort = 'latest',
    String? search,
  }) async {
    final response = await _client.dio.get(
      ApiConstants.maps,
      queryParameters: {
        'limit': limit,
        'sort': sort,
        if (cursor != null) 'cursor': cursor,
        if (visibility != null) 'visibility': visibility,
        if (search != null && search.trim().isNotEmpty)
          'search': search.trim(),
      },
    );
    return PagedResult.fromJson(
      response.data as Map<String, dynamic>,
      MapModel.fromJson,
    );
  }

  Future<MapModel> getMap(String mapId) async {
    final response = await _client.dio.get(ApiConstants.mapDetail(mapId));
    return MapModel.fromJson(response.data as Map<String, dynamic>);
  }

  Future<List<MapModel>> getMyMaps(String type) async {
    final response = await _client.dio.get(
      '/maps/me',
      queryParameters: {'type': type},
    );
    final list = response.data as List;
    return list
        .map((item) => MapModel.fromJson(item as Map<String, dynamic>))
        .toList();
  }

  Future<MapModel> createMap({
    required String title,
    String? description,
    List<String> tags = const [],
    required String visibility,
  }) async {
    final response = await _client.dio.post(
      ApiConstants.maps,
      data: {
        'title': title,
        'description': description,
        'tags': tags,
        'visibility': visibility,
      },
    );
    return MapModel.fromJson(response.data as Map<String, dynamic>);
  }

  Future<MapModel> updateMap(
    String mapId, {
    String? title,
    String? description,
    List<String>? tags,
    String? visibility,
  }) async {
    final response = await _client.dio.put(
      ApiConstants.mapDetail(mapId),
      data: {
        if (title != null) 'title': title,
        if (description != null) 'description': description,
        if (tags != null) 'tags': tags,
        if (visibility != null) 'visibility': visibility,
      },
    );
    return MapModel.fromJson(response.data as Map<String, dynamic>);
  }

  Future<bool> getMapMembership(String mapId) async {
    final response = await _client.dio.get('/maps/$mapId/membership');
    return response.data['isMember'] as bool? ?? false;
  }

  Future<bool> joinMap(String mapId) async {
    final response = await _client.dio.post('/maps/$mapId/join');
    return response.data['isMember'] as bool? ?? true;
  }

  Future<MapModel> joinMapByInviteCode(String inviteCode) async {
    final response = await _client.dio.post(
      '/maps/join-by-code',
      data: {'inviteCode': inviteCode.trim().toUpperCase()},
    );
    return MapModel.fromJson(response.data as Map<String, dynamic>);
  }

  Future<void> leaveMap(String mapId) async {
    await _client.dio.post('/maps/$mapId/leave');
  }

  Future<MapModel> regenerateInviteCode(String mapId) async {
    final response =
        await _client.dio.post('/maps/$mapId/invite-code/regenerate');
    return MapModel.fromJson(response.data as Map<String, dynamic>);
  }

  Future<void> deleteMap(String mapId, {bool force = false}) async {
    await _client.dio.delete(
      ApiConstants.mapDetail(mapId),
      queryParameters: force ? {'force': 'true'} : null,
    );
  }

  Future<List<MapMemberModel>> getMembers(String mapId) async {
    final response = await _client.dio.get('/maps/$mapId/members');
    final list = response.data as List;
    return list
        .map((item) => MapMemberModel.fromJson(item as Map<String, dynamic>))
        .toList();
  }

  Future<void> removeMember(String mapId, String userId) async {
    await _client.dio.delete('/maps/$mapId/members/$userId');
  }

  Future<void> reportUser(
    String userId, {
    required String reason,
    String? detail,
  }) async {
    await _client.dio.post(
      ApiConstants.reports,
      data: {
        'targetType': 'USER',
        'targetId': userId,
        'reason': reason,
        'detail': detail,
      },
    );
  }

  Future<MapModel> uploadCoverImage(String mapId, XFile imageFile) async {
    final bytes = await imageFile.readAsBytes();
    final formData = FormData.fromMap({
      'file': MultipartFile.fromBytes(
        bytes,
        filename: imageFile.name.isNotEmpty ? imageFile.name : 'cover.jpg',
        contentType: imageContentType(imageFile),
      ),
    });
    final response = await _client.dio.post('/maps/$mapId/cover', data: formData);
    return MapModel.fromJson(response.data as Map<String, dynamic>);
  }
}
