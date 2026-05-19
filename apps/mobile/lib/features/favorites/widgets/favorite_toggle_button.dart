import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers/favorites_provider.dart';
import '../services/favorites_service.dart';
import '../../maps/providers/maps_provider.dart';

class FavoriteToggleButton extends ConsumerStatefulWidget {
  final String mapId;

  const FavoriteToggleButton({
    super.key,
    required this.mapId,
  });

  @override
  ConsumerState<FavoriteToggleButton> createState() =>
      _FavoriteToggleButtonState();
}

class _FavoriteToggleButtonState extends ConsumerState<FavoriteToggleButton> {
  bool _loading = false;

  @override
  Widget build(BuildContext context) {
    final favorites = ref.watch(favoritesProvider).valueOrNull ?? [];
    String? favoriteId;
    for (final favorite in favorites) {
      if (favorite.mapId == widget.mapId) {
        favoriteId = favorite.id;
        break;
      }
    }
    final isFavorite = favoriteId != null;

    return IconButton(
      tooltip: isFavorite ? '즐겨찾기 해제' : '즐겨찾기 추가',
      icon: Icon(
        isFavorite ? Icons.bookmark : Icons.bookmark_outline,
        color: isFavorite ? const Color(0xFF2563EB) : null,
      ),
      onPressed: _loading
          ? null
          : () async {
              final messenger = ScaffoldMessenger.of(context);
              setState(() => _loading = true);
              try {
                if (isFavorite) {
                  await FavoritesService().removeFavorite(favoriteId!);
                } else {
                  await FavoritesService().addFavorite(widget.mapId);
                }
                ref.invalidate(favoritesProvider);
                ref.invalidate(mapsListProvider(MapVisibilityFilter.public));
                ref.invalidate(mapsListProvider(MapVisibilityFilter.private));
                ref.invalidate(mapDetailProvider(widget.mapId));
              } catch (error) {
                if (!mounted) return;
                messenger.showSnackBar(
                  SnackBar(
                    content: Text(
                      isFavorite
                          ? '즐겨찾기 해제 실패: $error'
                          : '즐겨찾기 추가 실패: $error',
                    ),
                    backgroundColor: Colors.red,
                  ),
                );
              } finally {
                if (mounted) setState(() => _loading = false);
              }
            },
    );
  }
}
