import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../shared/models/place_review_model.dart';
import '../../places/providers/places_provider.dart';

class MyReviewsScreen extends ConsumerWidget {
  const MyReviewsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final reviewsAsync = ref.watch(_myReviewsProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('작성한 리뷰')),
      body: reviewsAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => Center(child: Text('불러오기 실패: $error')),
        data: (reviews) {
          if (reviews.isEmpty) {
            return const Center(
              child: Text(
                '작성한 리뷰가 없습니다.',
                style: TextStyle(color: Colors.grey),
              ),
            );
          }

          return RefreshIndicator(
            onRefresh: () async => ref.invalidate(_myReviewsProvider),
            child: ListView.separated(
              padding: const EdgeInsets.all(16),
              itemCount: reviews.length,
              separatorBuilder: (_, __) => const SizedBox(height: 10),
              itemBuilder: (context, index) {
                final review = reviews[index];
                return _MyReviewCard(review: review);
              },
            ),
          );
        },
      ),
    );
  }
}

final _myReviewsProvider = FutureProvider<List<PlaceReviewModel>>((ref) {
  return ref.read(placesServiceProvider).getMyReviews();
});

class _MyReviewCard extends StatelessWidget {
  final PlaceReviewModel review;

  const _MyReviewCard({required this.review});

  @override
  Widget build(BuildContext context) {
    final place = review.place;

    return Card(
      child: InkWell(
        onTap: place == null ? null : () => context.push('/maps/${place.mapId}'),
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(
                      place?.name ?? '장소',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontWeight: FontWeight.w800),
                    ),
                  ),
                  Row(
                    children: List.generate(5, (index) {
                      return Icon(
                        index < review.rating
                            ? Icons.star
                            : Icons.star_border,
                        size: 16,
                        color: const Color(0xFFF59E0B),
                      );
                    }),
                  ),
                ],
              ),
              if (place?.map?.title != null) ...[
                const SizedBox(height: 4),
                Text(
                  place!.map!.title,
                  style: TextStyle(fontSize: 12, color: Colors.grey[600]),
                ),
              ],
              const SizedBox(height: 8),
              Text(review.content),
            ],
          ),
        ),
      ),
    );
  }
}
