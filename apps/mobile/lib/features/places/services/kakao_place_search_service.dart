import 'package:dio/dio.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/constants/api_constants.dart';
import '../../../shared/models/kakao_place_result.dart';

class KakaoPlaceSearchService {
  final _client = DioClient();

  Future<List<KakaoPlaceResult>> search(
    String query, {
    double? x,
    double? y,
    int? radius,
  }) async {
    try {
      final response = await _client.dio.get(
        ApiConstants.placeSearch,
        queryParameters: {
          'query': query,
          if (x != null) 'x': x.toString(),
          if (y != null) 'y': y.toString(),
          if (radius != null) 'radius': radius.toString(),
        },
      );

      final documents = (response.data['documents'] as List?) ?? [];
      return documents
          .map((e) => KakaoPlaceResult.fromJson(e as Map<String, dynamic>))
          .toList();
    } on DioException catch (e) {
      final serverMessage = e.response?.data is Map
          ? (e.response!.data as Map)['message']?.toString()
          : null;
      throw Exception(serverMessage ?? '서버 연결 실패 (${e.response?.statusCode ?? '네트워크 오류'})');
    }
  }
}
