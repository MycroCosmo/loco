import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../providers/favorites_provider.dart';
import '../services/favorites_service.dart';
import '../../../shared/widgets/loco_card.dart';

class FavoritesScreen extends ConsumerWidget {
  const FavoritesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final favAsync = ref.watch(favoritesProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('즐겨찾기')),
      body: favAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('불러오기 실패: $e')),
        data: (favorites) => favorites.isEmpty
            ? const Center(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(Icons.bookmark_border, size: 64, color: Colors.grey),
                    SizedBox(height: 12),
                    Text('즐겨찾기한 지도가 없어요.',
                        style: TextStyle(color: Colors.grey)),
                  ],
                ),
              )
            : RefreshIndicator(
                onRefresh: () async => ref.invalidate(favoritesProvider),
                child: ListView.builder(
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  itemCount: favorites.length,
                  itemBuilder: (context, index) {
                    final fav = favorites[index];
                    if (fav.map == null) return const SizedBox.shrink();
                    return LocoMapCard(
                      map: fav.map!,
                      onTap: () => context.push('/maps/${fav.mapId}'),
                      trailing: IconButton(
                        icon: const Icon(Icons.bookmark,
                            color: Color(0xFF2563EB)),
                        onPressed: () async {
                          await FavoritesService().removeFavorite(fav.id);
                          ref.invalidate(favoritesProvider);
                        },
                      ),
                    );
                  },
                ),
              ),
      ),
    );
  }
}
