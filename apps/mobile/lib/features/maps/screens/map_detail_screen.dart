import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:pointer_interceptor/pointer_interceptor.dart';
import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:share_plus/share_plus.dart' show Share;

import '../../../core/constants/api_constants.dart';
import '../../../shared/models/map_model.dart';
import '../../../shared/models/map_marker_model.dart';
import '../../../shared/models/map_member_model.dart';
import '../../../shared/models/map_viewport_model.dart';
import '../../../shared/models/place_model.dart';
import '../../../shared/models/place_review_model.dart';
import '../../../shared/widgets/cached_image.dart';
import '../../../shared/widgets/kakao_map_widget.dart';
import '../../../shared/widgets/visibility_badge.dart';
import '../../favorites/widgets/favorite_toggle_button.dart';
import '../../places/providers/places_provider.dart';
import '../../profile/providers/user_provider.dart';
import '../providers/maps_provider.dart';

class MapDetailScreen extends ConsumerStatefulWidget {
  final String mapId;

  const MapDetailScreen({super.key, required this.mapId});

  @override
  ConsumerState<MapDetailScreen> createState() => _MapDetailScreenState();
}

class _MapDetailScreenState extends ConsumerState<MapDetailScreen> {
  PlaceModel? _selectedPlace;
  final _placeDetailScrollController = ScrollController();
  final _placeSearchCtrl = TextEditingController();
  Timer? _markerDebounce;
  List<MapMarkerModel>? _mapMarkers;
  int _markerRequestSeq = 0;
  double? _mapCenterLat;
  double? _mapCenterLng;
  int _mapLevel = 5;
  bool _markerSheetOpen = false;
  PlaceSortOption _placeSort = PlaceSortOption.latest;
  String _placeSearch = '';
  String? _placeTag;
  bool _regeneratingInviteCode = false;
  bool _deletingPlaceImage = false;

  void _shareMap(MapModel map) {
    if (!map.isPublic && map.inviteCode != null) {
      Share.share(
        '🗺️ LOCO 지도방 "${map.title}"에 초대합니다!\n초대 코드: ${map.inviteCode}',
      );
    } else {
      Share.share(
        '🗺️ LOCO에서 "${map.title}" 지도방을 확인해보세요!',
      );
    }
  }

  Future<void> _showMembersSheet(MapModel map, String? currentUserId) async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(18)),
      ),
      builder: (_) => PointerInterceptor(
        child: _MembersSheet(
          map: map,
          currentUserId: currentUserId,
          onMemberRemoved: () => ref.invalidate(mapDetailProvider(widget.mapId)),
        ),
      ),
    );
  }

  Future<void> _deleteMap(MapModel map) async {
    final placesState = ref.read(placesListProvider(widget.mapId)).valueOrNull;
    final currentUserId = ref.read(userProvider).valueOrNull?.id;
    final otherCount = placesState?.items
            .where((p) => p.createdById != currentUserId)
            .length ??
        0;

    final result = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('지도방 삭제'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (otherCount > 0) ...[
              Text(
                '다른 멤버가 추가한 장소 $otherCount개가 있습니다.\n강제 삭제 시 해당 장소도 모두 삭제됩니다.',
                style: const TextStyle(color: Colors.deepOrange),
              ),
              const SizedBox(height: 12),
            ],
            const Text('삭제한 데이터는 복구할 수 없습니다.'),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('취소'),
          ),
          if (otherCount > 0)
            TextButton(
              onPressed: () => Navigator.pop(ctx, 'force'),
              child: const Text('강제 삭제', style: TextStyle(color: Colors.red)),
            ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, 'normal'),
            child: const Text('삭제', style: TextStyle(color: Colors.red)),
          ),
        ],
      ),
    );

    if (result == null || !mounted) return;

    try {
      await ref
          .read(mapsServiceProvider)
          .deleteMap(widget.mapId, force: result == 'force');
      if (!mounted) return;
      context.go('/maps');
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('삭제 실패: $e'), backgroundColor: Colors.red),
      );
    }
  }

  @override
  void dispose() {
    _markerDebounce?.cancel();
    _placeDetailScrollController.dispose();
    _placeSearchCtrl.dispose();
    super.dispose();
  }

  void _selectPlace(PlaceModel place) {
    setState(() => _selectedPlace = place);
    if (_placeDetailScrollController.hasClients) {
      _placeDetailScrollController.jumpTo(0);
    }
  }

  Future<void> _selectMarker(
    MapMarkerModel marker,
    List<PlaceModel> loadedPlaces,
  ) async {
    if (_markerSheetOpen || _selectedPlace != null) {
      return;
    }

    if (marker.isCluster) {
      await _showMarkerCluster(marker);
      return;
    }

    if (marker.isGroup) {
      await _showMarkerGroup(marker);
      return;
    }

    for (final place in loadedPlaces) {
      if (place.id == marker.id) {
        _selectPlace(place);
        return;
      }
    }

    try {
      final place =
          await ref.read(placesServiceProvider).getPlace(marker.id);
      if (!mounted) return;
      _selectPlace(place);
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('장소 불러오기 실패: $error'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }

  Future<void> _showMarkerGroup(MapMarkerModel marker) async {
    if (_markerSheetOpen || _selectedPlace != null) return;

    try {
      final places = await ref.read(placesServiceProvider).getMarkerGroupPlaces(
              widget.mapId,
              latitude: marker.latitude,
              longitude: marker.longitude,
              groupKey: marker.groupKey,
            );
      if (!mounted) return;

      _markerSheetOpen = true;
      try {
        await showModalBottomSheet<void>(
          context: context,
          isDismissible: true,
          enableDrag: true,
          showDragHandle: true,
          builder: (context) {
            return SafeArea(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 20),
                child: places.isEmpty
                    ? const Padding(
                        padding: EdgeInsets.symmetric(vertical: 32),
                        child: Center(child: Text('같은 위치의 장소가 없습니다.')),
                      )
                    : ListView.separated(
                        shrinkWrap: true,
                        itemCount: places.length,
                        separatorBuilder: (_, __) => const Divider(height: 1),
                        itemBuilder: (context, index) {
                          final place = places[index];
                          return ListTile(
                            contentPadding: EdgeInsets.zero,
                            title: Text(place.name),
                            subtitle: Text(place.roadAddress ?? place.address),
                            onTap: () {
                              Navigator.of(context).pop();
                              _selectPlace(place);
                            },
                          );
                        },
                      ),
              ),
            );
          },
        );
      } finally {
        _markerSheetOpen = false;
      }
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('장소 묶음 불러오기 실패: $error'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }

  Future<void> _showMarkerCluster(MapMarkerModel marker) async {
    if (marker.isOverflowCluster ||
        marker.precision == null ||
        marker.cellLatKey == null ||
        marker.cellLngKey == null) {
      await _showClusterSheet(
        marker: marker,
        title: '${marker.count}개 장소',
        description: '이 지역에 장소가 많아요. 지도를 확대하면 더 자세히 볼 수 있습니다.',
      );
      return;
    }

    try {
      final result =
          await ref.read(placesServiceProvider).getMarkerClusterPlaces(
                widget.mapId,
                precision: marker.precision!,
                cellLatKey: marker.cellLatKey!,
                cellLngKey: marker.cellLngKey!,
              );
      if (!mounted) return;

      await _showClusterSheet(
        marker: marker,
        title: '${result.count}개 장소',
        description: result.hasMore ? '대표 장소 50개를 먼저 표시합니다.' : null,
        places: result.items,
      );
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('클러스터 불러오기 실패: $error'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }

  Future<void> _showClusterSheet({
    required MapMarkerModel marker,
    required String title,
    String? description,
    List<PlaceModel> places = const [],
  }) async {
    if (_markerSheetOpen || _selectedPlace != null) return;

    _markerSheetOpen = true;
    try {
      await showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        isDismissible: true,
        enableDrag: true,
        showDragHandle: true,
        builder: (context) {
          return SafeArea(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 20),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          title,
                          style:
                              Theme.of(context).textTheme.titleMedium?.copyWith(
                                    fontWeight: FontWeight.w800,
                                  ),
                        ),
                      ),
                      IconButton(
                        tooltip: '닫기',
                        onPressed: () => Navigator.of(context).pop(),
                        icon: const Icon(Icons.close),
                      ),
                    ],
                  ),
                  if (description != null) ...[
                    const SizedBox(height: 8),
                    Text(
                      description,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color: Colors.grey[600],
                          ),
                    ),
                  ],
                  const SizedBox(height: 12),
                  if (places.isNotEmpty)
                    ConstrainedBox(
                      constraints: BoxConstraints(
                        maxHeight: MediaQuery.sizeOf(context).height * 0.45,
                      ),
                      child: ListView.separated(
                        shrinkWrap: true,
                        itemCount: places.length,
                        separatorBuilder: (_, __) => const Divider(height: 1),
                        itemBuilder: (context, index) {
                          final place = places[index];
                          return ListTile(
                            contentPadding: EdgeInsets.zero,
                            title: Text(place.name),
                            subtitle: Text(place.roadAddress ?? place.address),
                            onTap: () {
                              Navigator.of(context).pop();
                              _selectPlace(place);
                            },
                          );
                        },
                      ),
                    )
                  else
                    const Padding(
                      padding: EdgeInsets.symmetric(vertical: 20),
                      child: Center(child: Text('확대해서 장소를 확인해 주세요.')),
                    ),
                  const SizedBox(height: 12),
                  Align(
                    alignment: Alignment.centerRight,
                    child: OutlinedButton.icon(
                      onPressed: () {
                        Navigator.of(context).pop();
                        _focusMarker(marker);
                      },
                      icon: const Icon(Icons.zoom_in_map),
                      label: const Text('지도에서 확대 보기'),
                    ),
                  )
                ],
              ),
            ),
          );
        },
      );
    } finally {
      _markerSheetOpen = false;
    }
  }

  void _focusMarker(MapMarkerModel marker) {
    setState(() {
      _mapCenterLat = marker.latitude;
      _mapCenterLng = marker.longitude;
      _mapLevel = (_mapLevel - 2).clamp(1, 14).toInt();
    });
  }

  Future<void> _deletePlaceImage(String imageUrl) async {
    if (_deletingPlaceImage || _selectedPlace == null) return;
    setState(() => _deletingPlaceImage = true);
    try {
      final updated = await ref.read(placesServiceProvider).deletePlaceImage(
            _selectedPlace!.id,
            imageUrl,
          );
      if (!mounted) return;
      setState(() {
        _selectedPlace = updated;
        _deletingPlaceImage = false;
      });
      ref.read(placesListProvider(widget.mapId).notifier).refresh();
    } catch (error) {
      if (!mounted) return;
      setState(() => _deletingPlaceImage = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('사진 삭제 실패: $error'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }

  void _handleMapIdle(MapViewportModel viewport) {
    _mapCenterLat = viewport.centerLat;
    _mapCenterLng = viewport.centerLng;
    _mapLevel = viewport.level;
    _markerDebounce?.cancel();
    _markerDebounce = Timer(
      const Duration(milliseconds: 300),
      () => _loadMarkersForViewport(viewport),
    );
  }

  Future<void> _loadMarkersForViewport(MapViewportModel viewport) async {
    final requestSeq = ++_markerRequestSeq;
    try {
      final markers = await ref
          .read(placesServiceProvider)
          .getPlaceMarkers(widget.mapId, viewport);
      if (!mounted || requestSeq != _markerRequestSeq) return;
      setState(() => _mapMarkers = markers);
    } catch (_) {
      // Keep the current markers if a transient viewport request fails.
    }
  }

  void _closePlaceDetail() {
    setState(() => _selectedPlace = null);
  }

  Future<void> _regenerateInviteCode(MapModel map) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) {
        return AlertDialog(
          title: const Text('초대 코드 재생성'),
          content: const Text('기존 초대 코드는 더 이상 사용할 수 없게 됩니다. 새로 만들까요?'),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('취소'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: const Text('재생성'),
            ),
          ],
        );
      },
    );

    if (confirmed != true || !mounted) return;

    setState(() => _regeneratingInviteCode = true);
    try {
      final updatedMap =
          await ref.read(mapsServiceProvider).regenerateInviteCode(map.id);
      await Clipboard.setData(
        ClipboardData(text: updatedMap.inviteCode ?? ''),
      );
      ref.invalidate(mapDetailProvider(widget.mapId));
      ref.invalidate(mapsListProvider(MapVisibilityFilter.private));
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('새 초대 코드를 생성하고 복사했습니다.')),
      );
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('초대 코드 재생성 실패: $error'),
          backgroundColor: Colors.red,
        ),
      );
    } finally {
      if (mounted) {
        setState(() => _regeneratingInviteCode = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final mapAsync = ref.watch(mapDetailProvider(widget.mapId));
    final placesAsync = ref.watch(placesListProvider(widget.mapId));
    final userAsync = ref.watch(userProvider);

    return mapAsync.when(
      loading: () => const Scaffold(
        body: Center(child: CircularProgressIndicator()),
      ),
      error: (error, _) => Scaffold(
        body: Center(child: Text('오류: $error')),
      ),
      data: (map) {
        final currentUserId = userAsync.valueOrNull?.id;
        final isOwner = currentUserId == map.ownerId;

        return Scaffold(
          appBar: AppBar(
            leading: _selectedPlace != null
                ? IconButton(
                    icon: const Icon(Icons.arrow_back),
                    tooltip: '장소 목록으로',
                    onPressed: _closePlaceDetail,
                  )
                : null,
            title: Row(
              children: [
                Expanded(
                  child: Text(
                    map.title,
                    style: const TextStyle(fontWeight: FontWeight.bold),
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                VisibilityBadge(isPublic: map.isPublic),
              ],
            ),
            actions: [
              if (isOwner && !map.isPublic && map.inviteCode != null)
                IconButton(
                  icon: const Icon(Icons.copy_outlined),
                  tooltip: '초대 코드 복사',
                  onPressed: () async {
                    await Clipboard.setData(
                      ClipboardData(text: map.inviteCode!),
                    );
                    if (!context.mounted) return;
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text('초대 코드를 복사했습니다.')),
                    );
                  },
                ),
              if (isOwner && !map.isPublic)
                IconButton(
                  icon: _regeneratingInviteCode
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.autorenew_outlined),
                  tooltip: '초대 코드 재생성',
                  onPressed: _regeneratingInviteCode
                      ? null
                      : () => _regenerateInviteCode(map),
                ),
              // 공유 버튼 (방장 + 멤버 모두)
              if (currentUserId != null)
                IconButton(
                  icon: const Icon(Icons.share_outlined),
                  tooltip: '공유',
                  onPressed: () => _shareMap(map),
                ),
              if (isOwner)
                PopupMenuButton<String>(
                  tooltip: '더 보기',
                  onSelected: (value) async {
                    if (value == 'members') {
                      await _showMembersSheet(map, currentUserId);
                    } else if (value == 'edit') {
                      final updated =
                          await context.push<bool>('/maps/${map.id}/edit');
                      if (updated == true) {
                        ref.invalidate(mapDetailProvider(widget.mapId));
                        ref.invalidate(
                            mapsListProvider(MapVisibilityFilter.public));
                        ref.invalidate(
                            mapsListProvider(MapVisibilityFilter.private));
                      }
                    } else if (value == 'delete') {
                      await _deleteMap(map);
                    }
                  },
                  itemBuilder: (context) => [
                    const PopupMenuItem(
                      value: 'members',
                      child: ListTile(
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        leading: Icon(Icons.group_outlined),
                        title: Text('멤버 관리'),
                      ),
                    ),
                    const PopupMenuItem(
                      value: 'edit',
                      child: ListTile(
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        leading: Icon(Icons.edit_outlined),
                        title: Text('지도방 수정'),
                      ),
                    ),
                    const PopupMenuItem(
                      value: 'delete',
                      child: ListTile(
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        leading: Icon(Icons.delete_outline, color: Colors.red),
                        title: Text('삭제', style: TextStyle(color: Colors.red)),
                      ),
                    ),
                  ],
                )
              else if (currentUserId != null)
                FavoriteToggleButton(
                  mapId: map.id,
                ),
            ],
          ),
          body: PopScope(
            canPop: _selectedPlace == null,
            onPopInvokedWithResult: (didPop, result) {
              if (didPop) return;
              if (_selectedPlace != null) {
                _closePlaceDetail();
              }
            },
            child: Stack(
            children: [
              Positioned.fill(
                child: placesAsync.maybeWhen(
                  data: (placeState) {
                    final places = placeState.items;
                    final markers = _mapMarkers == null
                        ? places.map(MapMarkerModel.fromPlace).toList()
                        : _mapMarkers!;
                    return KakaoMapWidget(
                      latitude: _mapCenterLat ??
                          (places.isNotEmpty ? places.first.latitude : 37.5665),
                      longitude: _mapCenterLng ??
                          (places.isNotEmpty ? places.first.longitude : 126.9780),
                      level: _mapLevel,
                      markers: markers,
                      onMarkerTap: (marker) => _selectMarker(marker, places),
                      onMapIdle: _handleMapIdle,
                      draggable: true,
                      zoomable: true,
                      showZoomControls: false,
                    );
                  },
                  orElse: () => const ColoredBox(
                    color: Color(0xFFE5E7EB),
                  ),
                ),
              ),
              if (_selectedPlace == null)
                Align(
                  alignment: Alignment.bottomCenter,
                  child: PointerInterceptor(
                    child: DraggableScrollableSheet(
                      expand: false,
                      minChildSize: 0.32,
                      initialChildSize: 0.45,
                      maxChildSize: 0.88,
                      builder: (context, scrollController) {
                        return _MapBottomSheet(
                          child: placesAsync.when(
                            loading: () => const Center(
                                child: CircularProgressIndicator()),
                            error: (error, _) =>
                                Center(child: Text('장소 불러오기 실패: $error')),
                            data: (placeState) {
                              final places = placeState.items;
                              return _PlacesListPanel(
                                map: map,
                                mapId: widget.mapId,
                                places: places,
                                selectedPlace: _selectedPlace,
                                scrollController: scrollController,
                                hasMore: placeState.hasMore,
                                isLoadingMore: placeState.isLoadingMore,
                                loadMoreError: placeState.loadMoreError,
                                sort: _placeSort,
                                searchController: _placeSearchCtrl,
                                searchText: _placeSearch,
                                selectedTag: _placeTag,
                                onSortChanged: (sort) {
                                  setState(() => _placeSort = sort);
                                  ref
                                      .read(
                                        placesListProvider(widget.mapId)
                                            .notifier,
                                      )
                                      .setSort(sort);
                                },
                                onApplySearch: () {
                                  final query = _placeSearchCtrl.text.trim();
                                  setState(() => _placeSearch = query);
                                  ref
                                      .read(
                                        placesListProvider(widget.mapId)
                                            .notifier,
                                      )
                                      .setSearch(query);
                                },
                                onClearSearch: () {
                                  _placeSearchCtrl.clear();
                                  setState(() => _placeSearch = '');
                                  ref
                                      .read(
                                        placesListProvider(widget.mapId)
                                            .notifier,
                                      )
                                      .setSearch('');
                                },
                                onTagChanged: (tag) {
                                  setState(() => _placeTag = tag);
                                  ref
                                      .read(
                                        placesListProvider(widget.mapId)
                                            .notifier,
                                      )
                                      .setTag(tag);
                                },
                                onLoadMore: () => ref
                                    .read(
                                      placesListProvider(widget.mapId).notifier,
                                    )
                                    .loadMore(),
                                onSelectPlace: _selectPlace,
                                onShowMembers: currentUserId == null
                                    ? null
                                    : () => _showMembersSheet(map, currentUserId),
                              );
                            },
                          ),
                        );
                      },
                    ),
                  ),
                ),
              if (_selectedPlace != null)
                Positioned(
                  left: 0,
                  top: 0,
                  right: 0,
                  height: 250,
                  child: IgnorePointer(
                    ignoring: _selectedPlace == null,
                    child: PointerInterceptor(
                      child: _MapImagePreview(
                        place: _selectedPlace!,
                        onClose: _closePlaceDetail,
                        canDelete: userAsync.valueOrNull?.id == _selectedPlace?.createdById,
                        onDeleteImage: _deletePlaceImage,
                      ),
                    ),
                  ),
                ),
              if (_selectedPlace != null)
                Positioned.fill(
                  top: 250,
                  child: PointerInterceptor(
                    child: _FixedPlaceDetailSheet(
                      child: _PlaceDetailPanel(
                        map: map,
                        place: _selectedPlace!,
                        scrollController: _placeDetailScrollController,
                        onClose: _closePlaceDetail,
                        onChanged: () {
                          _closePlaceDetail();
                          ref
                              .read(
                                placesListProvider(widget.mapId).notifier,
                              )
                              .refresh();
                        },
                        onPlaceUpdated: (place) {
                          setState(() => _selectedPlace = place);
                          ref
                              .read(
                                placesListProvider(widget.mapId).notifier,
                              )
                              .refresh();
                        },
                      ),
                    ),
                  ),
                ),
            ],
            ),
          ),
        );
      },
    );
  }
}

class _PlacesListPanel extends ConsumerWidget {
  final MapModel map;
  final String mapId;
  final List<PlaceModel> places;
  final PlaceModel? selectedPlace;
  final ScrollController scrollController;
  final bool hasMore;
  final bool isLoadingMore;
  final Object? loadMoreError;
  final PlaceSortOption sort;
  final TextEditingController searchController;
  final String searchText;
  final String? selectedTag;
  final ValueChanged<PlaceSortOption> onSortChanged;
  final VoidCallback onApplySearch;
  final VoidCallback onClearSearch;
  final ValueChanged<String?> onTagChanged;
  final VoidCallback onLoadMore;
  final ValueChanged<PlaceModel> onSelectPlace;
  final VoidCallback? onShowMembers;

  const _PlacesListPanel({
    required this.map,
    required this.mapId,
    required this.places,
    required this.selectedPlace,
    required this.scrollController,
    required this.hasMore,
    required this.isLoadingMore,
    required this.loadMoreError,
    required this.sort,
    required this.searchController,
    required this.searchText,
    required this.selectedTag,
    required this.onSortChanged,
    required this.onApplySearch,
    required this.onClearSearch,
    required this.onTagChanged,
    required this.onLoadMore,
    required this.onSelectPlace,
    required this.onShowMembers,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final currentUserId = ref.watch(userProvider).valueOrNull?.id;
    final isOwner = currentUserId == map.ownerId;
    final membershipAsync = ref.watch(mapMembershipProvider(mapId));
    final isMember = isOwner || (membershipAsync.valueOrNull ?? false);
    final canAddPlace = isOwner || isMember;
    final canJoin = currentUserId != null && map.isPublic && !canAddPlace;
    final availableTags = {
      ...places.expand((place) => place.tags),
      if (selectedTag != null) selectedTag!,
    }.where((tag) => tag.trim().isNotEmpty).toList()
      ..sort();

    return NotificationListener<ScrollNotification>(
      onNotification: (notification) {
        if (hasMore && notification.metrics.extentAfter < 500) {
          onLoadMore();
        }
        return false;
      },
      child: CustomScrollView(
        controller: scrollController,
        slivers: [
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          '장소 목록',
                          style: Theme.of(context)
                              .textTheme
                              .titleMedium
                              ?.copyWith(fontWeight: FontWeight.bold),
                        ),
                      ),
                      TextButton.icon(
                        style: TextButton.styleFrom(
                          visualDensity: VisualDensity.compact,
                          padding: const EdgeInsets.symmetric(horizontal: 8),
                        ),
                        onPressed: isMember ? onShowMembers : null,
                        icon: const Icon(Icons.group_outlined, size: 16),
                        label: Text('멤버 ${map.membersCount}명'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Row(
                    children: [
                      DropdownButton<PlaceSortOption>(
                        value: sort,
                        underline: const SizedBox.shrink(),
                        items: PlaceSortOption.values
                            .map(
                              (option) => DropdownMenuItem(
                                value: option,
                                child: Text(option.label),
                              ),
                            )
                            .toList(),
                        onChanged: (value) {
                          if (value != null) onSortChanged(value);
                        },
                      ),
                      const Spacer(),
                      if (canAddPlace)
                        FilledButton.icon(
                          icon: const Icon(Icons.add),
                          label: const Text('장소 추가'),
                          onPressed: () async {
                            await context.push('/maps/$mapId/places/add');
                            ref
                                .read(placesListProvider(mapId).notifier)
                                .refresh();
                          },
                        )
                      else if (canJoin)
                        FilledButton.icon(
                          icon: const Icon(Icons.group_add_outlined),
                          label: const Text('가입하고 장소 추가'),
                          onPressed: membershipAsync.isLoading
                              ? null
                              : () async {
                                  try {
                                    await ref
                                        .read(mapsServiceProvider)
                                        .joinMap(mapId);
                                    if (!context.mounted) return;
                                    ref.invalidate(mapMembershipProvider(mapId));
                                    await context
                                        .push('/maps/$mapId/places/add');
                                    if (!context.mounted) return;
                                    ref
                                        .read(
                                          placesListProvider(mapId).notifier,
                                        )
                                        .refresh();
                                  } catch (error) {
                                    if (!context.mounted) return;
                                    ScaffoldMessenger.of(context).showSnackBar(
                                      SnackBar(
                                        content: Text('지도방 가입 실패: $error'),
                                        backgroundColor: Colors.red,
                                      ),
                                    );
                                  }
                                },
                        ),
                    ],
                  ),
                ],
              ),
            ),
          ),
          SliverToBoxAdapter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(16, 4, 16, 10),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: TextField(
                          controller: searchController,
                          decoration: InputDecoration(
                            hintText: '장소명, 주소, 태그 검색',
                            prefixIcon: const Icon(Icons.search),
                            suffixIcon: searchText.isEmpty
                                ? null
                                : IconButton(
                                    icon: const Icon(Icons.clear),
                                    onPressed: onClearSearch,
                                  ),
                            border: const OutlineInputBorder(),
                            isDense: true,
                          ),
                          textInputAction: TextInputAction.search,
                          onSubmitted: (_) => onApplySearch(),
                        ),
                      ),
                      const SizedBox(width: 8),
                      SizedBox(
                        width: 44,
                        height: 44,
                        child: IconButton.filled(
                          onPressed: onApplySearch,
                          icon: const Icon(Icons.search),
                          tooltip: '검색',
                        ),
                      ),
                    ],
                  ),
                  if (availableTags.isNotEmpty) ...[
                    const SizedBox(height: 8),
                    SingleChildScrollView(
                      scrollDirection: Axis.horizontal,
                      child: Row(
                        children: [
                          FilterChip(
                            label: const Text('전체'),
                            selected: selectedTag == null,
                            onSelected: (_) => onTagChanged(null),
                          ),
                          const SizedBox(width: 6),
                          ...availableTags.map(
                            (tag) => Padding(
                              padding: const EdgeInsets.only(right: 6),
                              child: FilterChip(
                                label: Text('#$tag'),
                                selected: selectedTag == tag,
                                onSelected: (_) => onTagChanged(
                                  selectedTag == tag ? null : tag,
                                ),
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),
          if (places.isEmpty)
            const SliverFillRemaining(
              hasScrollBody: false,
              child: Center(
                child: Text(
                  '아직 추가된 장소가 없어요.',
                  style: TextStyle(color: Colors.grey),
                ),
              ),
            )
          else
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
              sliver: SliverList(
                delegate: SliverChildBuilderDelegate(
                  (context, index) {
                    if (index.isOdd) return const SizedBox(height: 10);

                    final place = places[index ~/ 2];
                    return _PlaceListItem(
                      place: place,
                      selected: selectedPlace?.id == place.id,
                      onTap: () => onSelectPlace(place),
                    );
                  },
                  childCount: places.length * 2 - 1,
                ),
              ),
            ),
          if (isLoadingMore)
            const SliverToBoxAdapter(
              child: Padding(
                padding: EdgeInsets.symmetric(vertical: 18),
                child: Center(child: CircularProgressIndicator()),
              ),
            )
          else if (loadMoreError != null)
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 12),
                child: Center(
                  child: TextButton(
                    onPressed: onLoadMore,
                    child: const Text('더 불러오기 재시도'),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _MapBottomSheet extends StatelessWidget {
  final Widget child;

  const _MapBottomSheet({required this.child});

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: const BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.vertical(top: Radius.circular(18)),
        boxShadow: [
          BoxShadow(
            color: Color(0x22000000),
            blurRadius: 16,
            offset: Offset(0, -4),
          ),
        ],
      ),
      child: Column(
        children: [
          const SizedBox(height: 8),
          Container(
            width: 42,
            height: 4,
            decoration: BoxDecoration(
              color: Color(0xFFD1D5DB),
              borderRadius: BorderRadius.circular(999),
            ),
          ),
          const SizedBox(height: 4),
          Expanded(child: child),
        ],
      ),
    );
  }
}

class _FixedPlaceDetailSheet extends StatelessWidget {
  final Widget child;

  const _FixedPlaceDetailSheet({required this.child});

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: const BoxDecoration(
        color: Colors.white,
        boxShadow: [
          BoxShadow(
            color: Color(0x22000000),
            blurRadius: 16,
            offset: Offset(0, -4),
          ),
        ],
      ),
      child: child,
    );
  }
}

class _MapImagePreview extends StatelessWidget {
  final PlaceModel place;
  final VoidCallback onClose;
  final bool canDelete;
  final Future<void> Function(String)? onDeleteImage;

  const _MapImagePreview({
    required this.place,
    required this.onClose,
    this.canDelete = false,
    this.onDeleteImage,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.white,
      child: Stack(
        children: [
          Positioned.fill(
            child: ColoredBox(
              color: Colors.white,
              child: Padding(
                padding:
                    const EdgeInsets.symmetric(horizontal: 18, vertical: 8),
                child: Center(
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 640),
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(10),
                      child: _ImageMosaic(
                        imageUrls: place.imageUrls,
                        canDelete: canDelete,
                        onDelete: onDeleteImage,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
          Positioned(
            left: 14,
            bottom: 12,
            right: 58,
            child: DecoratedBox(
              decoration: BoxDecoration(
                color: Colors.black.withOpacity(0.52),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Padding(
                padding:
                    const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      place.name,
                      style: const TextStyle(
                        color: Colors.white,
                        fontWeight: FontWeight.w800,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    const SizedBox(height: 2),
                    Text(
                      place.roadAddress ?? place.address,
                      style: const TextStyle(
                        color: Colors.white70,
                        fontSize: 12,
                      ),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
            ),
          ),
          Positioned(
            top: 10,
            right: 10,
            child: IconButton.filledTonal(
              onPressed: onClose,
              icon: const Icon(Icons.close),
              tooltip: '닫기',
            ),
          ),
        ],
      ),
    );
  }
}

class _PlaceDetailPanel extends ConsumerStatefulWidget {
  final MapModel map;
  final PlaceModel place;
  final ScrollController scrollController;
  final VoidCallback onClose;
  final VoidCallback onChanged;
  final ValueChanged<PlaceModel> onPlaceUpdated;

  const _PlaceDetailPanel({
    required this.map,
    required this.place,
    required this.scrollController,
    required this.onClose,
    required this.onChanged,
    required this.onPlaceUpdated,
  });

  @override
  ConsumerState<_PlaceDetailPanel> createState() => _PlaceDetailPanelState();
}

class _PlaceDetailPanelState extends ConsumerState<_PlaceDetailPanel> {
  final _reviewCtrl = TextEditingController();
  int _rating = 5;
  bool _submitting = false;

  @override
  void dispose() {
    _reviewCtrl.dispose();
    super.dispose();
  }

  Future<void> _submitReview({
    required bool canReview,
    required String? disabledReason,
  }) async {
    if (!canReview) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(disabledReason ?? '리뷰를 작성할 수 없습니다.')),
      );
      return;
    }

    final content = _reviewCtrl.text.trim();
    if (content.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('리뷰 내용을 입력해주세요.')),
      );
      return;
    }

    setState(() => _submitting = true);
    try {
      await ref.read(placesServiceProvider).submitPlaceReview(
            widget.place.id,
            rating: _rating,
            content: content,
          );
      _reviewCtrl.clear();
      ref.invalidate(placeReviewsProvider(widget.place.id));
      ref.read(placesListProvider(widget.map.id).notifier).refresh();
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('리뷰 저장 실패: $error')),
        );
      }
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  Future<void> _editPlace() async {
    final updated = await context.push<bool>(
      '/maps/${widget.map.id}/places/${widget.place.id}/edit',
      extra: widget.place,
    );
    if (updated == true) {
      widget.onChanged();
    }
  }

  Future<void> _deletePlace() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('장소 삭제'),
        content: const Text('이 장소를 삭제할까요? 삭제한 장소와 이미지는 복구할 수 없습니다.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('취소'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('삭제', style: TextStyle(color: Colors.red)),
          ),
        ],
      ),
    );

    if (confirmed != true) return;

    try {
      await ref.read(placesServiceProvider).deletePlace(widget.place.id);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('장소가 삭제되었습니다.')),
      );
      widget.onChanged();
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('장소 삭제 실패: $error'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }

  Future<void> _deleteMyReview() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('리뷰 삭제'),
        content: const Text('내 리뷰를 삭제할까요?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('취소'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('삭제', style: TextStyle(color: Colors.red)),
          ),
        ],
      ),
    );

    if (confirmed != true) return;

    try {
      await ref.read(placesServiceProvider).deleteMyPlaceReview(widget.place.id);
      ref.invalidate(placeReviewsProvider(widget.place.id));
      ref.read(placesListProvider(widget.map.id).notifier).refresh();
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('리뷰 삭제 실패: $error'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }

  Future<void> _reportPlace() async {
    final reasonCtrl = TextEditingController();
    final detailCtrl = TextEditingController();

    final submitted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('장소 신고'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: reasonCtrl,
              maxLength: 200,
              decoration: const InputDecoration(
                labelText: '신고 사유',
                hintText: '예: 잘못된 장소 정보입니다.',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: detailCtrl,
              maxLines: 3,
              maxLength: 1000,
              decoration: const InputDecoration(
                labelText: '상세 내용',
                border: OutlineInputBorder(),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('신고'),
          ),
        ],
      ),
    );

    final reason = reasonCtrl.text.trim();
    final detail = detailCtrl.text.trim();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      reasonCtrl.dispose();
      detailCtrl.dispose();
    });

    if (submitted != true) return;

    if (reason.length < 5) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('신고 사유를 5자 이상 입력해주세요.')),
      );
      return;
    }

    try {
      await ref.read(placesServiceProvider).reportPlace(
            widget.place.id,
            reason: reason,
            detail: detail.isEmpty ? null : detail,
          );
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('신고가 접수되었습니다.')),
      );
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('신고 실패: $error'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final reviewsAsync = ref.watch(placeReviewsProvider(widget.place.id));
    final currentUserId = ref.watch(userProvider).valueOrNull?.id;
    final isLoggedIn = currentUserId != null;
    final isOwner = currentUserId == widget.map.ownerId;
    final membershipAsync = ref.watch(mapMembershipProvider(widget.map.id));
    final isCheckingMembership =
        isLoggedIn && !isOwner && membershipAsync.isLoading;
    final isMember = isOwner || (membershipAsync.valueOrNull ?? false);
    final canReview = isLoggedIn && !isCheckingMembership && isMember;
    final isPlaceCreator = currentUserId == widget.place.createdById;
    final canEditPlace = isPlaceCreator;
    final canDeletePlace = isPlaceCreator || isOwner;
    final canReportPlace = isLoggedIn && !isPlaceCreator;
    final reviewDisabledReason = !isLoggedIn
        ? '로그인 후 리뷰를 작성할 수 있습니다.'
        : isCheckingMembership
            ? '리뷰 작성 권한을 확인하고 있습니다.'
            : !isMember
                ? '지도방에 가입한 멤버만 리뷰를 작성할 수 있습니다.'
                : null;

    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 10, 8, 4),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  '장소 상세',
                  style: Theme.of(context)
                      .textTheme
                      .titleMedium
                      ?.copyWith(fontWeight: FontWeight.bold),
                ),
              ),
              PopupMenuButton<String>(
                tooltip: '장소 메뉴',
                onSelected: (value) {
                  if (value == 'edit') _editPlace();
                  if (value == 'delete') _deletePlace();
                  if (value == 'report') _reportPlace();
                },
                itemBuilder: (context) => [
                  if (canEditPlace)
                    const PopupMenuItem(
                      value: 'edit',
                      child: ListTile(
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        leading: Icon(Icons.edit_outlined),
                        title: Text('수정'),
                      ),
                    ),
                  if (canDeletePlace)
                    const PopupMenuItem(
                      value: 'delete',
                      child: ListTile(
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        leading: Icon(Icons.delete_outline, color: Colors.red),
                        title: Text('삭제', style: TextStyle(color: Colors.red)),
                      ),
                    ),
                  if (canReportPlace)
                    const PopupMenuItem(
                      value: 'report',
                      child: ListTile(
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        leading: Icon(Icons.flag_outlined),
                        title: Text('신고'),
                      ),
                    ),
                ],
              ),
            ],
          ),
        ),
        Expanded(
          child: ListView(
            controller: widget.scrollController,
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
            children: [
              Text(
                widget.place.name,
                style: Theme.of(context)
                    .textTheme
                    .titleLarge
                    ?.copyWith(fontWeight: FontWeight.w800),
              ),
              const SizedBox(height: 4),
              Text(
                widget.place.roadAddress ?? widget.place.address,
                style: TextStyle(color: Colors.grey[700]),
              ),
              const SizedBox(height: 10),
              _PlaceAuthor(author: widget.place.createdBy),
              if (widget.place.memo != null &&
                  widget.place.memo!.isNotEmpty) ...[
                const SizedBox(height: 10),
                Text(widget.place.memo!),
              ],
              if (widget.place.tags.isNotEmpty) ...[
                const SizedBox(height: 10),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: widget.place.tags.map((tag) {
                    return Chip(
                      label: Text(tag, style: const TextStyle(fontSize: 11)),
                      visualDensity: VisualDensity.compact,
                      padding: EdgeInsets.zero,
                    );
                  }).toList(),
                ),
              ],
              const SizedBox(height: 16),
              _PlaceLocationSection(place: widget.place),
              const SizedBox(height: 16),
              reviewsAsync.when(
                loading: () => const LinearProgressIndicator(),
                error: (error, _) => Text('리뷰 불러오기 실패: $error'),
                data: (summary) => Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _ReviewsSummary(summary: summary),
                    const SizedBox(height: 18),
                    Text(
                      '내 리뷰',
                      style: Theme.of(context)
                          .textTheme
                          .titleMedium
                          ?.copyWith(fontWeight: FontWeight.w800),
                    ),
                    const SizedBox(height: 8),
                    _RatingInput(
                      rating: _rating,
                      enabled: canReview,
                      onChanged: (rating) => setState(() => _rating = rating),
                    ),
                    const SizedBox(height: 8),
                    Stack(
                      children: [
                        TextField(
                          enabled: canReview,
                          controller: _reviewCtrl,
                          maxLines: 3,
                          decoration: InputDecoration(
                            hintText:
                                reviewDisabledReason ?? '이 장소에 대한 리뷰를 남겨보세요.',
                            border: const OutlineInputBorder(),
                            contentPadding:
                                const EdgeInsets.fromLTRB(12, 12, 84, 12),
                            filled: !canReview,
                            fillColor:
                                !canReview ? const Color(0xFFF3F4F6) : null,
                          ),
                        ),
                        Positioned(
                          right: 8,
                          top: 8,
                          child: FilledButton(
                            onPressed: _submitting || !canReview
                                ? null
                                : () => _submitReview(
                                      canReview: canReview,
                                      disabledReason: reviewDisabledReason,
                                    ),
                            style: FilledButton.styleFrom(
                              minimumSize: const Size(60, 34),
                              padding:
                                  const EdgeInsets.symmetric(horizontal: 12),
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(8),
                              ),
                              visualDensity: VisualDensity.compact,
                            ),
                            child: _submitting
                                ? const SizedBox(
                                    width: 16,
                                    height: 16,
                                    child: CircularProgressIndicator(
                                      strokeWidth: 2,
                                    ),
                                  )
                                : const Text('저장'),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 18),
                    _ReviewsSection(
                      summary: summary,
                      currentUserId: currentUserId,
                      onDeleteMine: _deleteMyReview,
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _PlaceLocationSection extends StatelessWidget {
  final PlaceModel place;

  const _PlaceLocationSection({required this.place});

  Future<void> _copyAddress(BuildContext context, String address) async {
    await Clipboard.setData(ClipboardData(text: address));
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('주소를 복사했습니다.')),
    );
  }

  Future<void> _launchExternalUrl(BuildContext context, Uri uri) async {
    final opened = await launchUrl(uri, mode: LaunchMode.externalApplication);
    if (opened || !context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('외부 지도를 열 수 없습니다.')),
    );
  }

  Future<void> _callPlace(BuildContext context) async {
    final phone = place.phone?.trim();
    if (phone == null || phone.isEmpty) return;
    await _launchExternalUrl(context, Uri(scheme: 'tel', path: phone));
  }

  Future<void> _openKakaoMap(BuildContext context) async {
    final label = Uri.encodeComponent(place.name);
    await _launchExternalUrl(
      context,
      Uri.parse(
        'https://map.kakao.com/link/map/$label,${place.latitude},${place.longitude}',
      ),
    );
  }

  Future<void> _openNaverMap(BuildContext context) async {
    final query = Uri.encodeComponent(place.name);
    await _launchExternalUrl(
      context,
      Uri.parse(
        'https://map.naver.com/v5/search/$query?c=${place.longitude},${place.latitude},15,0,0,0,dh',
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final primaryAddress = place.roadAddress ?? place.address;
    final secondaryAddress =
        place.roadAddress != null && place.address.isNotEmpty
            ? place.address
            : null;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '위치',
          style: Theme.of(context)
              .textTheme
              .titleMedium
              ?.copyWith(fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 8),
        LayoutBuilder(
          builder: (context, constraints) {
            final shouldConstrainWidth = constraints.maxWidth > 640;
            return Align(
              alignment: Alignment.centerLeft,
              child: ConstrainedBox(
                constraints: BoxConstraints(
                  maxWidth: shouldConstrainWidth ? 520 : double.infinity,
                ),
                child: AspectRatio(
                  aspectRatio: 4 / 3,
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(10),
                    child: KakaoMapWidget(
                      latitude: place.latitude,
                      longitude: place.longitude,
                      level: 3,
                      markers: [MapMarkerModel.fromPlace(place)],
                      draggable: false,
                      zoomable: false,
                      showZoomControls: true,
                    ),
                  ),
                ),
              ),
            );
          },
        ),
        const SizedBox(height: 10),
        Container(
          width: double.infinity,
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: const Color(0xFFF9FAFB),
            borderRadius: BorderRadius.circular(8),
            border: Border.all(color: const Color(0xFFE5E7EB)),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Icon(Icons.place_outlined,
                  size: 20, color: Color(0xFF2563EB)),
              const SizedBox(width: 8),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      primaryAddress,
                      style: const TextStyle(fontWeight: FontWeight.w700),
                    ),
                    if (secondaryAddress != null) ...[
                      const SizedBox(height: 3),
                      Text(
                        secondaryAddress,
                        style: TextStyle(fontSize: 12, color: Colors.grey[600]),
                      ),
                    ],
                  ],
                ),
              ),
              const SizedBox(width: 8),
              OutlinedButton.icon(
                onPressed: () => _copyAddress(context, primaryAddress),
                icon: const Icon(Icons.copy, size: 16),
                label: const Text('복사'),
              ),
            ],
          ),
        ),
        const SizedBox(height: 10),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            if (place.phone != null && place.phone!.trim().isNotEmpty)
              OutlinedButton.icon(
                onPressed: () => _callPlace(context),
                icon: const Icon(Icons.phone_outlined, size: 16),
                label: const Text('전화'),
              ),
            OutlinedButton.icon(
              onPressed: () => _openKakaoMap(context),
              icon: const Icon(Icons.map_outlined, size: 16),
              label: const Text('카카오맵'),
            ),
            OutlinedButton.icon(
              onPressed: () => _openNaverMap(context),
              icon: const Icon(Icons.navigation_outlined, size: 16),
              label: const Text('네이버지도'),
            ),
          ],
        ),
      ],
    );
  }
}

class _PlaceAuthor extends StatelessWidget {
  final PlaceAuthorModel? author;

  const _PlaceAuthor({required this.author});

  @override
  Widget build(BuildContext context) {
    final author = this.author;
    final nickname = author?.nickname ?? '알 수 없음';
    final profileImageUrl = author?.profileImageUrl;
    final imageProvider = profileImageUrl == null || profileImageUrl.isEmpty
        ? null
        : CachedNetworkImageProvider(
            profileImageUrl.startsWith('/uploads/')
                ? ApiConstants.imageUrl(profileImageUrl)
                : profileImageUrl,
          );

    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        CircleAvatar(
          radius: 13,
          backgroundColor: const Color(0xFFEFF6FF),
          backgroundImage: imageProvider,
          child: imageProvider == null
              ? Text(
                  nickname.isNotEmpty ? nickname[0].toUpperCase() : '?',
                  style: const TextStyle(
                    color: Color(0xFF2563EB),
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                  ),
                )
              : null,
        ),
        const SizedBox(width: 6),
        Text(
          '$nickname 등록',
          style: TextStyle(fontSize: 12, color: Colors.grey[700]),
        ),
      ],
    );
  }
}

class _ReviewsSummary extends StatelessWidget {
  final PlaceReviewsSummary summary;

  const _ReviewsSummary({required this.summary});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        const Icon(Icons.star, color: Color(0xFFF59E0B), size: 22),
        const SizedBox(width: 5),
        Text(
          summary.reviewCount == 0
              ? '아직 별점 없음'
              : summary.averageRating.toStringAsFixed(1),
          style: const TextStyle(fontWeight: FontWeight.w800),
        ),
        const SizedBox(width: 8),
        Text(
          '리뷰 ${summary.reviewCount}',
          style: TextStyle(color: Colors.grey[600]),
        ),
      ],
    );
  }
}

class _ReviewsSection extends StatelessWidget {
  final PlaceReviewsSummary summary;
  final String? currentUserId;
  final VoidCallback onDeleteMine;

  const _ReviewsSection({
    required this.summary,
    required this.currentUserId,
    required this.onDeleteMine,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '리뷰 목록',
          style: Theme.of(context)
              .textTheme
              .titleMedium
              ?.copyWith(fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 10),
        if (summary.reviews.isEmpty)
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: const Color(0xFFF9FAFB),
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: const Color(0xFFE5E7EB)),
            ),
            child: Text(
              '아직 리뷰가 없습니다.',
              style: TextStyle(color: Colors.grey[600]),
            ),
          )
        else
          ...summary.reviews.map(
            (review) => _ReviewTile(
              review: review,
              canDelete: review.userId == currentUserId,
              onDelete: onDeleteMine,
            ),
          ),
      ],
    );
  }


}

class _ReviewTile extends StatelessWidget {
  final PlaceReviewModel review;
  final bool canDelete;
  final VoidCallback onDelete;

  const _ReviewTile({
    required this.review,
    required this.canDelete,
    required this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          border: Border.all(color: const Color(0xFFE5E7EB)),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    review.user?.nickname ?? '사용자',
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                ),
                Row(
                  children: List.generate(5, (index) {
                    return Icon(
                      index < review.rating ? Icons.star : Icons.star_border,
                      size: 15,
                      color: const Color(0xFFF59E0B),
                    );
                  }),
                ),
                if (canDelete) ...[
                  const SizedBox(width: 4),
                  IconButton(
                    tooltip: '리뷰 삭제',
                    visualDensity: VisualDensity.compact,
                    iconSize: 18,
                    onPressed: onDelete,
                    icon: const Icon(Icons.delete_outline, color: Colors.red),
                  ),
                ],
              ],
            ),
            const SizedBox(height: 6),
            Text(review.content),
          ],
        ),
      ),
    );
  }
}

class _RatingInput extends StatelessWidget {
  final int rating;
  final bool enabled;
  final ValueChanged<int> onChanged;

  const _RatingInput({
    required this.rating,
    required this.enabled,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: List.generate(5, (index) {
        final value = index + 1;
        return IconButton(
          visualDensity: VisualDensity.compact,
          onPressed: enabled ? () => onChanged(value) : null,
          icon: Icon(
            value <= rating ? Icons.star : Icons.star_border,
            color: enabled ? const Color(0xFFF59E0B) : Colors.grey[400],
          ),
        );
      }),
    );
  }
}

class _ImageMosaic extends StatelessWidget {
  final List<String> imageUrls;
  final bool canDelete;
  final Future<void> Function(String imageUrl)? onDelete;

  const _ImageMosaic({
    required this.imageUrls,
    this.canDelete = false,
    this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    if (imageUrls.isEmpty) {
      return const _PlaceImageFallback();
    }

    if (imageUrls.length == 1) {
      return _MosaicTile(
        imageUrl: imageUrls.first,
        fit: BoxFit.contain,
        backgroundColor: const Color(0xFFF3F4F6),
        onTap: () => _openCarousel(context, 0),
        onDelete: canDelete ? () => onDelete?.call(imageUrls.first) : null,
      );
    }

    if (imageUrls.length == 2) {
      return Row(
        children: [
          Expanded(
            child: _MosaicTile(
              imageUrl: imageUrls[0],
              onTap: () => _openCarousel(context, 0),
              onDelete: canDelete ? () => onDelete?.call(imageUrls[0]) : null,
            ),
          ),
          const SizedBox(width: 3),
          Expanded(
            child: _MosaicTile(
              imageUrl: imageUrls[1],
              onTap: () => _openCarousel(context, 1),
              onDelete: canDelete ? () => onDelete?.call(imageUrls[1]) : null,
            ),
          ),
        ],
      );
    }

    return Row(
      children: [
        Expanded(
          flex: 2,
          child: _MosaicTile(
            imageUrl: imageUrls[0],
            onTap: () => _openCarousel(context, 0),
            onDelete: canDelete ? () => onDelete?.call(imageUrls[0]) : null,
          ),
        ),
        const SizedBox(width: 3),
        Expanded(
          child: Column(
            children: [
              Expanded(
                child: _MosaicTile(
                  imageUrl: imageUrls[1],
                  onTap: () => _openCarousel(context, 1),
                  onDelete: canDelete ? () => onDelete?.call(imageUrls[1]) : null,
                ),
              ),
              const SizedBox(height: 3),
              Expanded(
                child: _MosaicTile(
                  imageUrl: imageUrls[2],
                  extraCount: imageUrls.length > 3 ? imageUrls.length - 3 : 0,
                  onTap: () => _openCarousel(context, 2),
                  onDelete: canDelete ? () => onDelete?.call(imageUrls[2]) : null,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  void _openCarousel(BuildContext context, int initialIndex) {
    showDialog<void>(
      context: context,
      barrierColor: Colors.black,
      builder: (_) => PointerInterceptor(
        child: _ImageCarouselDialog(
          imageUrls: imageUrls,
          initialIndex: initialIndex,
          canDelete: canDelete,
          onDelete: onDelete,
        ),
      ),
    );
  }
}

class _MosaicTile extends StatelessWidget {
  final String imageUrl;
  final int extraCount;
  final VoidCallback onTap;
  final VoidCallback? onDelete;
  final BoxFit fit;
  final Color backgroundColor;

  const _MosaicTile({
    required this.imageUrl,
    required this.onTap,
    this.onDelete,
    this.extraCount = 0,
    this.fit = BoxFit.cover,
    this.backgroundColor = Colors.black,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: ColoredBox(
        color: backgroundColor,
        child: Stack(
          fit: StackFit.expand,
          children: [
            CachedImage(
              url: ApiConstants.imageUrl(imageUrl),
              fit: fit,
              errorWidget: const _PlaceImageFallback(),
            ),
            if (extraCount > 0)
        Container(
                color: Colors.black.withOpacity(0.46),
                alignment: Alignment.center,
                child: Text(
                  '+$extraCount',
                  style: const TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.w900,
                    fontSize: 22,
                  ),
                ),
              ),
            if (onDelete != null)
              Positioned(
                top: 6,
                right: 6,
                child: PointerInterceptor(
                  child: Material(
                    color: Colors.black54,
                    shape: const CircleBorder(),
                    child: InkWell(
                      customBorder: const CircleBorder(),
                      onTap: onDelete,
                      child: const Padding(
                        padding: EdgeInsets.all(6),
                        child: Icon(
                          Icons.close,
                          color: Colors.white,
                          size: 16,
                        ),
                      ),
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _ImageCarouselDialog extends StatefulWidget {
  final List<String> imageUrls;
  final int initialIndex;
  final bool canDelete;
  final Future<void> Function(String imageUrl)? onDelete;

  const _ImageCarouselDialog({
    required this.imageUrls,
    required this.initialIndex,
    this.canDelete = false,
    this.onDelete,
  });

  @override
  State<_ImageCarouselDialog> createState() => _ImageCarouselDialogState();
}

class _ImageCarouselDialogState extends State<_ImageCarouselDialog> {
  late final PageController _controller;
  late int _index;

  @override
  void initState() {
    super.initState();
    _index = widget.initialIndex;
    _controller = PageController(initialPage: widget.initialIndex);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.black,
      child: Stack(
        children: [
          PageView.builder(
            controller: _controller,
            itemCount: widget.imageUrls.length,
            onPageChanged: (index) => setState(() => _index = index),
            itemBuilder: (context, index) {
              return Center(
                child: InteractiveViewer(
                  child: CachedImage(
                    url: ApiConstants.imageUrl(widget.imageUrls[index]),
                    fit: BoxFit.contain,
                    placeholder: const Center(
                      child: CircularProgressIndicator(color: Colors.white54),
                    ),
                    errorWidget: const Center(
                      child: Icon(Icons.broken_image_outlined,
                          color: Colors.white54, size: 48),
                    ),
                  ),
                ),
              );
            },
          ),
          Positioned(
            top: 36,
            left: 16,
            child: IconButton(
              onPressed: () => Navigator.of(context).pop(),
              icon: const Icon(Icons.close, color: Colors.white),
            ),
          ),
          Positioned(
            top: 46,
            right: 20,
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  '${_index + 1}/${widget.imageUrls.length}',
                  style: const TextStyle(color: Colors.white),
                ),
                if (widget.canDelete && widget.onDelete != null) ...[
                  const SizedBox(width: 8),
                  IconButton(
                    tooltip: '사진 삭제',
                    onPressed: () async {
                      final imageUrl = widget.imageUrls[_index];
                      await widget.onDelete!(imageUrl);
                      if (context.mounted) Navigator.of(context).pop();
                    },
                    icon: const Icon(Icons.delete_outline, color: Colors.white),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _PlaceListItem extends StatelessWidget {
  final PlaceModel place;
  final bool selected;
  final VoidCallback onTap;

  const _PlaceListItem({
    required this.place,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color:
          selected ? const Color(0xFF2563EB).withOpacity(0.06) : Colors.white,
      borderRadius: BorderRadius.circular(8),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(8),
        child: Container(
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(
            border: Border.all(
              color: selected
                  ? const Color(0xFF2563EB).withOpacity(0.35)
                  : const Color(0xFFE5E7EB),
            ),
            borderRadius: BorderRadius.circular(8),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              _PlaceThumbnail(place: place, size: 108),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            place.name,
                            style: const TextStyle(fontWeight: FontWeight.w700),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                        if (place.category != null &&
                            place.category!.isNotEmpty) ...[
                          const SizedBox(width: 8),
                          Chip(
                            label: Text(
                              place.category!,
                              style: const TextStyle(fontSize: 11),
                            ),
                            visualDensity: VisualDensity.compact,
                            padding: EdgeInsets.zero,
                          ),
                        ],
                      ],
                    ),
                    const SizedBox(height: 4),
                    Text(
                      place.roadAddress ?? place.address,
                      style: TextStyle(fontSize: 12, color: Colors.grey[700]),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                    const SizedBox(height: 5),
                    _PlaceAuthor(author: place.createdBy),
                    const SizedBox(height: 5),
                    _PlaceRatingSummary(
                      averageRating: place.averageRating,
                      reviewCount: place.reviewCount,
                    ),
                    if (place.tags.isNotEmpty) ...[
                      const SizedBox(height: 7),
                      SizedBox(
                        height: 28,
                        child: ListView.separated(
                          scrollDirection: Axis.horizontal,
                          physics: const NeverScrollableScrollPhysics(),
                          itemCount:
                              place.tags.length > 3 ? 4 : place.tags.length,
                          separatorBuilder: (_, __) => const SizedBox(width: 6),
                          itemBuilder: (context, index) {
                            if (index == 3) {
                              return Chip(
                                label: Text(
                                  '+${place.tags.length - 3}',
                                  style: const TextStyle(fontSize: 11),
                                ),
                                visualDensity: VisualDensity.compact,
                                padding: EdgeInsets.zero,
                              );
                            }

                            return Chip(
                              label: Text(
                                place.tags[index],
                                style: const TextStyle(fontSize: 11),
                              ),
                              visualDensity: VisualDensity.compact,
                              padding: EdgeInsets.zero,
                            );
                          },
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _PlaceRatingSummary extends StatelessWidget {
  final double averageRating;
  final int reviewCount;

  const _PlaceRatingSummary({
    required this.averageRating,
    required this.reviewCount,
  });

  @override
  Widget build(BuildContext context) {
    final hasReviews = reviewCount > 0;

    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        const Icon(Icons.star, size: 15, color: Color(0xFFF59E0B)),
        const SizedBox(width: 4),
        Text(
          hasReviews ? averageRating.toStringAsFixed(1) : '아직 별점 없음',
          style: TextStyle(
            fontSize: 12,
            fontWeight: FontWeight.w700,
            color: hasReviews ? const Color(0xFF111827) : Colors.grey[600],
          ),
        ),
        const SizedBox(width: 6),
        Text(
          '리뷰 $reviewCount',
          style: TextStyle(fontSize: 12, color: Colors.grey[600]),
        ),
      ],
    );
  }
}

class _PlaceThumbnail extends StatelessWidget {
  final PlaceModel place;
  final double size;

  const _PlaceThumbnail({required this.place, required this.size});

  @override
  Widget build(BuildContext context) {
    final hasImage = place.imageUrls.isNotEmpty;

    return Stack(
      children: [
        ClipRRect(
          borderRadius: BorderRadius.circular(8),
          child: SizedBox(
            width: size,
            height: size,
            child: hasImage
                ? CachedImage(
                    url: ApiConstants.imageUrl(place.imageUrls.first),
                    errorWidget: const _PlaceImageFallback(),
                  )
                : const _PlaceImageFallback(),
          ),
        ),
        if (place.imageUrls.length > 1)
          Positioned(
            right: 4,
            bottom: 4,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
              decoration: BoxDecoration(
                color: Colors.black.withOpacity(0.62),
                borderRadius: BorderRadius.circular(999),
              ),
              child: Text(
                '+${place.imageUrls.length - 1}',
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 11,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
          ),
      ],
    );
  }
}

class _PlaceImageFallback extends StatelessWidget {
  const _PlaceImageFallback();

  @override
  Widget build(BuildContext context) {
    return Container(
      color: const Color(0xFFEFF6FF),
      alignment: Alignment.center,
      child: const Icon(Icons.place, color: Color(0xFF2563EB)),
    );
  }
}

// ─── 멤버 관리 바텀 시트 ───────────────────────────────────────────────────────

class _MembersSheet extends ConsumerStatefulWidget {
  final MapModel map;
  final String? currentUserId;
  final VoidCallback onMemberRemoved;

  const _MembersSheet({
    required this.map,
    required this.currentUserId,
    required this.onMemberRemoved,
  });

  @override
  ConsumerState<_MembersSheet> createState() => _MembersSheetState();
}

class _MembersSheetState extends ConsumerState<_MembersSheet> {
  late Future<List<MapMemberModel>> _membersFuture;

  @override
  void initState() {
    super.initState();
    _membersFuture = _loadMembers();
  }

  Future<List<MapMemberModel>> _loadMembers() =>
      ref.read(mapsServiceProvider).getMembers(widget.map.id);

  List<MapMemberModel> _sortMembers(List<MapMemberModel> members) {
    final sorted = [...members];
    sorted.sort((a, b) {
      final aSelf = a.id == widget.currentUserId;
      final bSelf = b.id == widget.currentUserId;
      if (aSelf != bSelf) return aSelf ? -1 : 1;
      if (a.isOwner != b.isOwner) return a.isOwner ? -1 : 1;
      return a.joinedAt.compareTo(b.joinedAt);
    });
    return sorted;
  }

  Future<void> _kickMember(MapMemberModel member) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('멤버 내보내기'),
        content: Text('${member.nickname}님을 지도방에서 내보낼까요?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('취소'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('내보내기'),
          ),
        ],
      ),
    );

    if (confirmed != true || !mounted) return;

    try {
      await ref.read(mapsServiceProvider).removeMember(widget.map.id, member.id);
      widget.onMemberRemoved();
      setState(() {
        _membersFuture = _loadMembers();
      });
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('${member.nickname}님을 내보냈습니다.')),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('내보내기 실패: $e'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }

  Future<void> _reportMember(MapMemberModel member) async {
    final reasonCtrl = TextEditingController();
    final detailCtrl = TextEditingController();

    final submitted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('${member.nickname}님 신고'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: reasonCtrl,
              maxLength: 200,
              decoration: const InputDecoration(
                labelText: '신고 사유',
                hintText: '예: 부적절한 활동을 했습니다.',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 10),
            TextField(
              controller: detailCtrl,
              maxLines: 3,
              maxLength: 1000,
              decoration: const InputDecoration(
                labelText: '상세 내용',
                border: OutlineInputBorder(),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('취소'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('신고'),
          ),
        ],
      ),
    );

    final reason = reasonCtrl.text.trim();
    final detail = detailCtrl.text.trim();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      reasonCtrl.dispose();
      detailCtrl.dispose();
    });

    if (submitted != true) return;

    if (reason.length < 5) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('신고 사유를 5자 이상 입력해주세요.')),
      );
      return;
    }

    try {
      await ref.read(mapsServiceProvider).reportUser(
            member.id,
            reason: reason,
            detail: detail.isEmpty ? null : detail,
          );
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('신고가 접수되었습니다.')),
      );
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('신고 실패: $error'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final isOwner = widget.currentUserId == widget.map.ownerId;

    return DraggableScrollableSheet(
      expand: false,
      initialChildSize: 0.5,
      minChildSize: 0.35,
      maxChildSize: 0.85,
      builder: (context, scrollController) {
        return Material(
          color: Theme.of(context).colorScheme.surface,
          child: Column(
            children: [
              const SizedBox(height: 8),
              Container(
                width: 42,
                height: 4,
                decoration: BoxDecoration(
                  color: const Color(0xFFD1D5DB),
                  borderRadius: BorderRadius.circular(999),
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
                child: Row(
                  children: [
                    const Icon(Icons.group_outlined, size: 20),
                    const SizedBox(width: 8),
                    Text(
                      '멤버 목록',
                      style: Theme.of(context)
                          .textTheme
                          .titleMedium
                          ?.copyWith(fontWeight: FontWeight.bold),
                    ),
                    const Spacer(),
                    IconButton(
                      tooltip: '닫기',
                      onPressed: () => Navigator.of(context).maybePop(),
                      icon: const Icon(Icons.close),
                    ),
                  ],
                ),
              ),
              const Divider(height: 1),
              Expanded(
                child: FutureBuilder<List<MapMemberModel>>(
                  future: _membersFuture,
                  builder: (context, snapshot) {
                    if (snapshot.connectionState == ConnectionState.waiting) {
                      return const Center(child: CircularProgressIndicator());
                    }
                    if (snapshot.hasError) {
                      return Center(child: Text('불러오기 실패: ${snapshot.error}'));
                    }
                    final members = _sortMembers(snapshot.data ?? []);

                    return ListView.separated(
                      controller: scrollController,
                      itemCount: members.length,
                      separatorBuilder: (_, __) => const Divider(height: 1),
                      itemBuilder: (context, index) {
                        final member = members[index];
                        final isSelf = member.id == widget.currentUserId;

                        return ListTile(
                          leading: CircleAvatar(
                            radius: 20,
                            backgroundColor: const Color(0xFFEFF6FF),
                            backgroundImage: member.profileImageUrl != null &&
                                    member.profileImageUrl!.isNotEmpty
                                ? NetworkImage(
                                    ApiConstants.imageUrl(
                                        member.profileImageUrl!),
                                  )
                                : null,
                            child: member.profileImageUrl == null ||
                                    member.profileImageUrl!.isEmpty
                                ? Text(
                                    member.nickname.isNotEmpty
                                        ? member.nickname[0].toUpperCase()
                                        : '?',
                                    style: const TextStyle(
                                      color: Color(0xFF2563EB),
                                      fontWeight: FontWeight.bold,
                                    ),
                                  )
                                : null,
                          ),
                          title: Row(
                            children: [
                              Text(member.nickname),
                              if (member.isOwner) ...[
                                const SizedBox(width: 6),
                                Container(
                                  padding: const EdgeInsets.symmetric(
                                      horizontal: 6, vertical: 2),
                                  decoration: BoxDecoration(
                                    color: const Color(0xFFEFF6FF),
                                    borderRadius: BorderRadius.circular(999),
                                  ),
                                  child: const Text(
                                    '방장',
                                    style: TextStyle(
                                      fontSize: 11,
                                      color: Color(0xFF2563EB),
                                      fontWeight: FontWeight.bold,
                                    ),
                                  ),
                                ),
                              ],
                              if (isSelf) ...[
                                const SizedBox(width: 6),
                                Container(
                                  padding: const EdgeInsets.symmetric(
                                      horizontal: 6, vertical: 2),
                                  decoration: BoxDecoration(
                                    color: const Color(0xFFF3F4F6),
                                    borderRadius: BorderRadius.circular(999),
                                  ),
                                  child: const Text(
                                    '나',
                                    style: TextStyle(
                                      fontSize: 11,
                                      color: Color(0xFF6B7280),
                                    ),
                                  ),
                                ),
                              ],
                            ],
                          ),
                          subtitle: Text(
                            '${member.joinedAt.year}.${member.joinedAt.month.toString().padLeft(2, '0')}.${member.joinedAt.day.toString().padLeft(2, '0')} 가입',
                            style: const TextStyle(fontSize: 12),
                          ),
                          trailing: isSelf
                              ? null
                              : isOwner && !member.isOwner
                                  ? IconButton(
                                      icon: const Icon(
                                        Icons.person_remove_outlined,
                                        color: Colors.red,
                                      ),
                                      tooltip: '내보내기',
                                      onPressed: () => _kickMember(member),
                                    )
                                  : IconButton(
                                      icon: const Icon(Icons.flag_outlined),
                                      tooltip: '신고',
                                      onPressed: () => _reportMember(member),
                                    ),
                        );
                      },
                    );
                  },
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}
