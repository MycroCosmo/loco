import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../shared/models/map_model.dart';
import '../../../shared/models/paged_list_state.dart';
import '../services/maps_service.dart';

final mapsServiceProvider = Provider<MapsService>((ref) => MapsService());

enum MapVisibilityFilter {
  public('PUBLIC'),
  private('PRIVATE');

  final String apiValue;

  const MapVisibilityFilter(this.apiValue);
}

enum MapSortOption {
  latest('latest', '최신순'),
  popular('popular', '인기순'),
  places('places', '장소 많은순');

  final String apiValue;
  final String label;

  const MapSortOption(this.apiValue, this.label);
}

final mapsListProvider =
    StateNotifierProvider.family<MapsListNotifier,
        AsyncValue<PagedListState<MapModel>>, MapVisibilityFilter>(
  (ref, visibility) => MapsListNotifier(
    ref.read(mapsServiceProvider),
    visibility,
  ),
);

class MapsListNotifier
    extends StateNotifier<AsyncValue<PagedListState<MapModel>>> {
  final MapsService _service;
  final MapVisibilityFilter _visibility;
  MapSortOption _sort = MapSortOption.latest;
  String _search = '';
  int _requestSeq = 0;

  MapsListNotifier(this._service, this._visibility)
      : super(const AsyncValue.loading()) {
    refresh();
  }

  Future<void> refresh() async {
    final requestSeq = ++_requestSeq;
    state = const AsyncValue.loading();
    try {
      final page = await _service.getMaps(
        visibility: _visibility.apiValue,
        sort: _sort.apiValue,
        search: _search,
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
      final page = await _service.getMaps(
        cursor: current.nextCursor,
        visibility: _visibility.apiValue,
        sort: _sort.apiValue,
        search: _search,
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

  Future<void> setSort(MapSortOption sort) async {
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
}

final mapDetailProvider =
    FutureProvider.family<MapModel, String>((ref, mapId) async {
  return ref.read(mapsServiceProvider).getMap(mapId);
});

final mapMembershipProvider =
    FutureProvider.family<bool, String>((ref, mapId) async {
  return ref.read(mapsServiceProvider).getMapMembership(mapId);
});
