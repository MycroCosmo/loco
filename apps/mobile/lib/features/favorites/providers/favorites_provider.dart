import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../services/favorites_service.dart';
import '../../../shared/models/favorite_model.dart';

final favoritesProvider = FutureProvider<List<FavoriteModel>>((ref) async {
  return FavoritesService().getFavorites();
});
