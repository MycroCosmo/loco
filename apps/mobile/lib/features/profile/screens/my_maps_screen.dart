import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../shared/models/map_model.dart';
import '../../../shared/widgets/loco_card.dart';
import '../../maps/providers/maps_provider.dart';

class MyMapsScreen extends ConsumerStatefulWidget {
  final String type;

  const MyMapsScreen({super.key, required this.type});

  bool get _isJoined => type == 'joined';

  @override
  ConsumerState<MyMapsScreen> createState() => _MyMapsScreenState();
}

class _MyMapsScreenState extends ConsumerState<MyMapsScreen> {
  final Set<String> _leavingMapIds = {};

  bool get _isJoined => widget._isJoined;

  Future<void> _confirmAndLeave(MapModel map) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) {
        return AlertDialog(
          title: const Text('지도방 나가기'),
          content: Text('"${map.title}" 지도방에서 나갈까요?'),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(false),
              child: const Text('취소'),
            ),
            FilledButton(
              style: FilledButton.styleFrom(backgroundColor: Colors.red),
              onPressed: () => Navigator.of(dialogContext).pop(true),
              child: const Text('나가기'),
            ),
          ],
        );
      },
    );

    if (confirmed != true || !mounted) return;

    setState(() => _leavingMapIds.add(map.id));
    try {
      await ref.read(mapsServiceProvider).leaveMap(map.id);
      ref.invalidate(_myMapsProvider(widget.type));
      ref.invalidate(mapsListProvider(MapVisibilityFilter.public));
      ref.invalidate(mapsListProvider(MapVisibilityFilter.private));
      ref.invalidate(mapDetailProvider(map.id));
      ref.invalidate(mapMembershipProvider(map.id));
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('지도방에서 나갔습니다.')),
      );
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('지도방 나가기 실패: $error'),
          backgroundColor: Colors.red,
        ),
      );
    } finally {
      if (mounted) {
        setState(() => _leavingMapIds.remove(map.id));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final mapsAsync = ref.watch(_myMapsProvider(widget.type));
    final title = _isJoined ? '참여한 지도방' : '만든 지도방';

    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: mapsAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => Center(child: Text('불러오기 실패: $error')),
        data: (maps) {
          if (maps.isEmpty) {
            return Center(
              child: Text(
                _isJoined ? '참여 중인 지도방이 없습니다.' : '만든 지도방이 없습니다.',
                style: const TextStyle(color: Colors.grey),
              ),
            );
          }

          return RefreshIndicator(
            onRefresh: () async => ref.invalidate(_myMapsProvider(widget.type)),
            child: ListView.builder(
              padding: const EdgeInsets.symmetric(vertical: 8),
              itemCount: maps.length,
              itemBuilder: (context, index) {
                final map = maps[index];
                final isLeaving = _leavingMapIds.contains(map.id);
                return LocoMapCard(
                  map: map,
                  onTap: () => context.push('/maps/${map.id}'),
                  trailing: _isJoined
                      ? OutlinedButton.icon(
                          onPressed:
                              isLeaving ? null : () => _confirmAndLeave(map),
                          icon: isLeaving
                              ? const SizedBox(
                                  width: 14,
                                  height: 14,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : const Icon(Icons.logout, size: 16),
                          label: Text(isLeaving ? '나가는 중' : '나가기'),
                          style: OutlinedButton.styleFrom(
                            foregroundColor: Colors.red,
                            side: const BorderSide(color: Colors.red),
                            minimumSize: const Size(82, 34),
                            padding: const EdgeInsets.symmetric(horizontal: 10),
                            visualDensity: VisualDensity.compact,
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(8),
                            ),
                          ),
                        )
                      : null,
                );
              },
            ),
          );
        },
      ),
    );
  }
}

final _myMapsProvider =
    FutureProvider.family<List<MapModel>, String>((ref, type) {
  return ref.read(mapsServiceProvider).getMyMaps(type);
});
