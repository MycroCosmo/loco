import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../shared/models/map_model.dart';
import '../../../shared/models/paged_list_state.dart';
import '../../../shared/widgets/loco_card.dart';
import '../../favorites/widgets/favorite_toggle_button.dart';
import '../../profile/providers/user_provider.dart';
import '../providers/maps_provider.dart';

class MapListScreen extends ConsumerStatefulWidget {
  const MapListScreen({super.key});

  @override
  ConsumerState<MapListScreen> createState() => _MapListScreenState();
}

class _MapListScreenState extends ConsumerState<MapListScreen> {
  final _searchCtrl = TextEditingController();
  String _draftQuery = '';
  String _query = '';
  MapSortOption _publicMapSort = MapSortOption.latest;
  MapSortOption _privateMapSort = MapSortOption.latest;
  bool _joiningByInviteCode = false;

  @override
  void dispose() {
    _searchCtrl.dispose();
    super.dispose();
  }

  void _applySearch() {
    final query = _draftQuery.trim();
    setState(() => _query = query);
    ref.read(mapsListProvider(MapVisibilityFilter.public).notifier).setSearch(query);
    ref.read(mapsListProvider(MapVisibilityFilter.private).notifier).setSearch(query);
  }

  void _clearSearch() {
    _searchCtrl.clear();
    setState(() {
      _draftQuery = '';
      _query = '';
    });
    ref.read(mapsListProvider(MapVisibilityFilter.public).notifier).setSearch('');
    ref.read(mapsListProvider(MapVisibilityFilter.private).notifier).setSearch('');
  }

  Future<void> _showInviteCodeDialog() async {
    final controller = TextEditingController();
    String? errorText;

    final inviteCode = await showDialog<String>(
      context: context,
      builder: (dialogContext) {
        return StatefulBuilder(
          builder: (context, setDialogState) {
            void submit() {
              final inviteCode = controller.text.trim().toUpperCase();
              if (inviteCode.isEmpty) {
                setDialogState(() => errorText = '초대 코드를 입력해주세요.');
                return;
              }

              FocusManager.instance.primaryFocus?.unfocus();
              Navigator.of(dialogContext, rootNavigator: true).pop(inviteCode);
            }

            return AlertDialog(
              title: const Text('초대 코드로 참여'),
              content: TextField(
                controller: controller,
                autofocus: true,
                textCapitalization: TextCapitalization.characters,
                decoration: InputDecoration(
                  labelText: '초대 코드',
                  hintText: '예: ABCD1234',
                  errorText: errorText,
                  border: const OutlineInputBorder(),
                ),
                textInputAction: TextInputAction.done,
                onSubmitted: (_) => submit(),
              ),
              actions: [
                TextButton(
                  onPressed: () {
                    FocusManager.instance.primaryFocus?.unfocus();
                    Navigator.of(dialogContext, rootNavigator: true).pop();
                  },
                  child: const Text('취소'),
                ),
                FilledButton(
                  onPressed: submit,
                  child: const Text('참여'),
                ),
              ],
            );
          },
        );
      },
    );

    controller.dispose();
    if (inviteCode == null || !mounted) return;

    await WidgetsBinding.instance.endOfFrame;
    if (!mounted) return;

    setState(() => _joiningByInviteCode = true);
    try {
      final joinedMap =
          await ref.read(mapsServiceProvider).joinMapByInviteCode(inviteCode);
      await ref
          .read(mapsListProvider(MapVisibilityFilter.private).notifier)
          .refresh();
      if (!mounted) return;
      setState(() => _joiningByInviteCode = false);

      await WidgetsBinding.instance.endOfFrame;
      if (!mounted) return;
      context.push('/maps/${joinedMap.id}');
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(_inviteCodeErrorMessage(error)),
          backgroundColor: Colors.red,
        ),
      );
      setState(() => _joiningByInviteCode = false);
    }
  }

  String _inviteCodeErrorMessage(Object error) {
    if (error is DioException) {
      final data = error.response?.data;
      if (data is Map<String, dynamic>) {
        final message = data['message'];
        if (message is String) return message;
        if (message is List && message.isNotEmpty) {
          return message.join('\n');
        }
      }
    }
    return '초대 코드 확인에 실패했습니다.';
  }

  Widget _buildSearchHeader() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 10),
      child: Row(
        children: [
          Expanded(
            child: TextField(
              controller: _searchCtrl,
              decoration: InputDecoration(
                hintText: '지도방 이름 또는 태그 검색',
                prefixIcon: const Icon(Icons.search),
                suffixIcon: _draftQuery.isEmpty
                    ? null
                    : IconButton(
                        icon: const Icon(Icons.clear),
                        onPressed: _clearSearch,
                      ),
                border: const OutlineInputBorder(),
                isDense: true,
              ),
              textInputAction: TextInputAction.search,
              onChanged: (value) {
                setState(() => _draftQuery = value);
              },
              onSubmitted: (_) => _applySearch(),
            ),
          ),
          const SizedBox(width: 8),
          SizedBox(
            width: 48,
            height: 48,
            child: IconButton.filled(
              onPressed: _applySearch,
              icon: const Icon(Icons.search),
              tooltip: '검색',
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildInviteCodeButton() {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 6),
      child: SizedBox(
        width: double.infinity,
        height: 48,
        child: OutlinedButton.icon(
          onPressed: _joiningByInviteCode ? null : _showInviteCodeDialog,
          icon: const Icon(Icons.lock_open_outlined, size: 18),
          label: _joiningByInviteCode
              ? const Text('참여 중...')
              : const Text('초대 코드로 비공개방 참여'),
        ),
      ),
    );
  }

  Widget _buildMapsTab({
    required AsyncValue<PagedListState<MapModel>> mapsAsync,
    required MapVisibilityFilter visibility,
    required String emptyMessage,
    required String emptySearchMessage,
    required bool showInviteCodeButton,
    required String? currentUserId,
    required MapSortOption sort,
    required ValueChanged<MapSortOption> onSortChanged,
  }) {
    return mapsAsync.when(
      loading: () => const Center(child: CircularProgressIndicator()),
      error: (error, _) => _MapListError(
        message: '불러오기 실패: $error',
        onRetry: () =>
            ref.read(mapsListProvider(visibility).notifier).refresh(),
      ),
      data: (pageState) {
        final maps = pageState.items;
        final isSearchEmpty = _query.isNotEmpty && maps.isEmpty;
        final isTabEmpty = pageState.items.isEmpty;

        return NotificationListener<ScrollNotification>(
          onNotification: (notification) {
            if (notification.metrics.axis == Axis.vertical &&
                pageState.hasMore &&
                notification.metrics.extentAfter < 500) {
              ref.read(mapsListProvider(visibility).notifier).loadMore();
            }
            return false;
          },
          child: RefreshIndicator(
            onRefresh: () =>
                ref.read(mapsListProvider(visibility).notifier).refresh(),
            child: ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.only(top: 2, bottom: 88),
              children: [
                if (showInviteCodeButton) _buildInviteCodeButton(),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 6),
                  child: Align(
                    alignment: Alignment.centerRight,
                    child: DropdownButton<MapSortOption>(
                      value: sort,
                      underline: const SizedBox.shrink(),
                      items: MapSortOption.values
                          .map(
                            (option) => DropdownMenuItem(
                              value: option,
                              child: Text(option.label),
                            ),
                          )
                          .toList(),
                      onChanged: (value) {
                        if (value == null) return;
                        onSortChanged(value);
                        ref
                            .read(mapsListProvider(visibility).notifier)
                            .setSort(value);
                      },
                    ),
                  ),
                ),
                if (isTabEmpty || isSearchEmpty)
                  _EmptyMapListMessage(
                    icon: isSearchEmpty
                        ? Icons.search_off_outlined
                        : Icons.map_outlined,
                    message: isSearchEmpty ? emptySearchMessage : emptyMessage,
                  )
                else
                  ...maps.map((map) {
                    return LocoMapCard(
                      map: map,
                      onTap: () => context.push('/maps/${map.id}'),
                      trailing:
                          currentUserId != null && currentUserId != map.ownerId
                              ? FavoriteToggleButton(mapId: map.id)
                              : null,
                    );
                  }),
                if (pageState.isLoadingMore)
                  const Padding(
                    padding: EdgeInsets.symmetric(vertical: 18),
                    child: Center(child: CircularProgressIndicator()),
                  )
                else if (pageState.loadMoreError != null)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    child: Center(
                      child: TextButton(
                        onPressed: () => ref
                            .read(mapsListProvider(visibility).notifier)
                            .loadMore(),
                        child: const Text('더 불러오기 재시도'),
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

  @override
  Widget build(BuildContext context) {
    final publicMapsAsync =
        ref.watch(mapsListProvider(MapVisibilityFilter.public));
    final privateMapsAsync =
        ref.watch(mapsListProvider(MapVisibilityFilter.private));
    final currentUserId = ref.watch(userProvider).valueOrNull?.id;

    return DefaultTabController(
      length: 2,
      child: Scaffold(
        appBar: AppBar(
          title:
              const Text('LOCO', style: TextStyle(fontWeight: FontWeight.bold)),
          actions: [
            IconButton(
              icon: const Icon(Icons.bookmark_outline),
              onPressed: () => context.push('/favorites'),
            ),
            IconButton(
              icon: const Icon(Icons.person_outline),
              onPressed: () => context.push('/profile'),
            ),
          ],
        ),
        body: Column(
          children: [
            _buildSearchHeader(),
            const TabBar(
              tabs: [
                Tab(text: '공개방'),
                Tab(text: '비공개방'),
              ],
            ),
            Expanded(
              child: TabBarView(
                children: [
                  _buildMapsTab(
                    mapsAsync: publicMapsAsync,
                    visibility: MapVisibilityFilter.public,
                    emptyMessage: '아직 공개 지도방이 없어요.\n+ 버튼으로 첫 지도방을 만들어 보세요.',
                    emptySearchMessage: '공개방 검색 결과가 없습니다.',
                    showInviteCodeButton: false,
                    currentUserId: currentUserId,
                    sort: _publicMapSort,
                    onSortChanged: (sort) =>
                        setState(() => _publicMapSort = sort),
                  ),
                  _buildMapsTab(
                    mapsAsync: privateMapsAsync,
                    visibility: MapVisibilityFilter.private,
                    emptyMessage: '참여 중인 비공개 지도방이 없어요.\n초대 코드로 참여해 보세요.',
                    emptySearchMessage: '비공개방 검색 결과가 없습니다.',
                    showInviteCodeButton: true,
                    currentUserId: currentUserId,
                    sort: _privateMapSort,
                    onSortChanged: (sort) =>
                        setState(() => _privateMapSort = sort),
                  ),
                ],
              ),
            ),
          ],
        ),
        floatingActionButton: FloatingActionButton(
          onPressed: () async {
            await context.push('/maps/create');
            ref
                .read(mapsListProvider(MapVisibilityFilter.public).notifier)
                .refresh();
            ref
                .read(mapsListProvider(MapVisibilityFilter.private).notifier)
                .refresh();
          },
          backgroundColor: const Color(0xFF2563EB),
          child: const Icon(Icons.add, color: Colors.white),
        ),
      ),
    );
  }
}

class _MapListError extends StatelessWidget {
  final String message;
  final VoidCallback onRetry;

  const _MapListError({
    required this.message,
    required this.onRetry,
  });

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Icon(Icons.error_outline, size: 48, color: Colors.red),
            const SizedBox(height: 8),
            Text(message, textAlign: TextAlign.center),
            TextButton(
              onPressed: onRetry,
              child: const Text('다시 시도'),
            ),
          ],
        ),
      ),
    );
  }
}

class _EmptyMapListMessage extends StatelessWidget {
  final IconData icon;
  final String message;

  const _EmptyMapListMessage({
    required this.icon,
    required this.message,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 56),
      child: Center(
        child: Column(
          children: [
            Icon(icon, size: 56, color: Colors.grey),
            const SizedBox(height: 12),
            Text(
              message,
              textAlign: TextAlign.center,
              style: const TextStyle(color: Colors.grey),
            ),
          ],
        ),
      ),
    );
  }
}
