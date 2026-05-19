import '../../../core/network/dio_client.dart';
import '../../../core/constants/api_constants.dart';
import '../../../shared/models/favorite_model.dart';

class FavoritesService {
  final _client = DioClient();

  Future<List<FavoriteModel>> getFavorites() async {
    final response = await _client.dio.get(ApiConstants.favorites);
    final list = response.data as List;
    return list
        .map((e) => FavoriteModel.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<FavoriteModel> addFavorite(String mapId) async {
    final response = await _client.dio.post(
      ApiConstants.favorites,
      data: {'mapId': mapId},
    );
    return FavoriteModel.fromJson(response.data as Map<String, dynamic>);
  }

  Future<void> removeFavorite(String favoriteId) async {
    await _client.dio.delete(ApiConstants.favoriteDetail(favoriteId));
  }
}
