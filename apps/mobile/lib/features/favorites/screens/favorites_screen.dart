import '../../../core/utils/user_message.dart';
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
        error: (e, _) => Center(child: Text(userErrorMessage(e, action: '내용을 불러오지 못했어요.'))),
        data: (favorites) => favorites.isEmpty
            ? Center(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(Icons.bookmark_border, size: 64, color: Theme.of(context).colorScheme.onSurfaceVariant),
                    const SizedBox(height: 12),
                    Text('즐겨찾기한 지도방가 없어요.',
                        style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant)),
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
                      tooltip: '즐겨찾기 해제',
                        icon: Icon(Icons.bookmark,
                            color: Theme.of(context).colorScheme.primary),
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
