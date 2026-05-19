import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../shared/models/paged_list_state.dart';
import '../../../shared/models/place_model.dart';
import '../../../shared/models/place_review_model.dart';
import '../services/places_service.dart';

final placesServiceProvider = Provider<PlacesService>((ref) => PlacesService());

final placesListProvider = StateNotifierProvider.family<PlacesListNotifier,
    AsyncValue<PagedListState<PlaceModel>>, String>((ref, mapId) {
  return PlacesListNotifier(ref.read(placesServiceProvider), mapId);
});

enum PlaceSortOption {
  latest('latest', '최신순'),
  name('name', '이름순'),
  reviews('reviews', '리뷰 많은순');

  final String apiValue;
  final String label;

  const PlaceSortOption(this.apiValue, this.label);
}

class PlacesListNotifier
    extends StateNotifier<AsyncValue<PagedListState<PlaceModel>>> {
  final PlacesService _service;
  final String _mapId;
  PlaceSortOption _sort = PlaceSortOption.latest;
  String _search = '';
  String? _tag;
  int _requestSeq = 0;

  PlacesListNotifier(this._service, this._mapId)
      : super(const AsyncValue.loading()) {
    refresh();
  }

  Future<void> refresh() async {
    final requestSeq = ++_requestSeq;
    state = const AsyncValue.loading();
    try {
      final page = await _service.getPlaces(
        _mapId,
        sort: _sort.apiValue,
        search: _search,
        tag: _tag,
      );
      if (requestSeq != _requestSeq) return;
      state = AsyncValue.data(
        PagedListState(
          items: page.items,
          nextCursor: page.nextCursor,
          hasMore: page.hasMore,
        ),
      );
    } catch (error, stackTrace) {
      if (requestSeq != _requestSeq) return;
      state = AsyncValue.error(error, stackTrace);
    }
  }

  Future<void> loadMore() async {
    final current = state.valueOrNull;
    if (current == null || current.isLoadingMore || !current.hasMore) return;
    final requestSeq = _requestSeq;

    state = AsyncValue.data(
      current.copyWith(isLoadingMore: true, clearLoadMoreError: true),
    );

    try {
      final page = await _service.getPlaces(
        _mapId,
        cursor: current.nextCursor,
        sort: _sort.apiValue,
        search: _search,
        tag: _tag,
      );
      if (requestSeq != _requestSeq) return;
      state = AsyncValue.data(
        current.copyWith(
          items: [...current.items, ...page.items],
          nextCursor: page.nextCursor,
          clearNextCursor: page.nextCursor == null,
          hasMore: page.hasMore,
          isLoadingMore: false,
        ),
      );
    } catch (error) {
      if (requestSeq != _requestSeq) return;
      state = AsyncValue.data(
        current.copyWith(isLoadingMore: false, loadMoreError: error),
      );
    }
  }

  Future<void> setSort(PlaceSortOption sort) async {
    if (_sort == sort) return;
    _sort = sort;
    await refresh();
  }

  Future<void> setSearch(String search) async {
    final normalized = search.trim();
    if (_search == normalized) return;
    _search = normalized;
    _requestSeq++;
    await refresh();
  }

  Future<void> setTag(String? tag) async {
    final normalized = tag?.trim();
    final next = normalized == null || normalized.isEmpty ? null : normalized;
    if (_tag == next) return;
    _tag = next;
    _requestSeq++;
    await refresh();
  }

  Future<void> clearFilters() async {
    if (_search.isEmpty && _tag == null) return;
    _search = '';
    _tag = null;
    _requestSeq++;
    await refresh();
  }
}

final placeReviewsProvider =
    FutureProvider.family<PlaceReviewsSummary, String>((ref, placeId) async {
  return ref.read(placesServiceProvider).getPlaceReviews(placeId);
});
