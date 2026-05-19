import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/constants/api_constants.dart';
import '../../../shared/models/place_model.dart';
import '../../../shared/widgets/cached_image.dart';
import '../../places/providers/places_provider.dart';

class MyPlacesScreen extends ConsumerWidget {
  const MyPlacesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final placesAsync = ref.watch(_myPlacesProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('등록한 장소')),
      body: placesAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => Center(child: Text('불러오기 실패: $error')),
        data: (places) {
          if (places.isEmpty) {
            return const Center(
              child: Text(
                '등록한 장소가 없습니다.',
                style: TextStyle(color: Colors.grey),
              ),
            );
          }

          return RefreshIndicator(
            onRefresh: () async => ref.invalidate(_myPlacesProvider),
            child: ListView.separated(
              padding: const EdgeInsets.all(16),
              itemCount: places.length,
              separatorBuilder: (_, __) => const SizedBox(height: 10),
              itemBuilder: (context, index) {
                final place = places[index];
                return _MyPlaceCard(place: place);
              },
            ),
          );
        },
      ),
    );
  }
}

final _myPlacesProvider = FutureProvider<List<PlaceModel>>((ref) {
  return ref.read(placesServiceProvider).getMyPlaces();
});

class _MyPlaceCard extends StatelessWidget {
  final PlaceModel place;

  const _MyPlaceCard({required this.place});

  @override
  Widget build(BuildContext context) {
    final imageUrl = place.imageUrls.isEmpty ? null : place.imageUrls.first;

    return Card(
      child: ListTile(
        contentPadding: const EdgeInsets.all(12),
        leading: ClipRRect(
          borderRadius: BorderRadius.circular(8),
          child: SizedBox(
            width: 58,
            height: 58,
            child: imageUrl == null
                ? const ColoredBox(
                    color: Color(0xFFEFF6FF),
                    child: Icon(Icons.place, color: Color(0xFF2563EB)),
                  )
                : CachedImage(
                    url: ApiConstants.imageUrl(imageUrl),
                    errorWidget: const ColoredBox(
                      color: Color(0xFFEFF6FF),
                      child: Icon(Icons.place, color: Color(0xFF2563EB)),
                    ),
                  ),
          ),
        ),
        title: Text(
          place.name,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(fontWeight: FontWeight.w700),
        ),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const SizedBox(height: 4),
            Text(
              place.map?.title ?? '지도방',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
            Text(
              place.roadAddress ?? place.address,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ],
        ),
        onTap: () => context.push('/maps/${place.mapId}'),
      ),
    );
  }
}
