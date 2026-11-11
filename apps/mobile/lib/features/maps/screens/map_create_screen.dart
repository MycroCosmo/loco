import '../../../core/utils/user_message.dart';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';
import '../../../core/constants/api_constants.dart';
import '../../../shared/models/map_model.dart';
import '../providers/maps_provider.dart';
import '../services/maps_service.dart';

class MapCreateScreen extends ConsumerStatefulWidget {
  final String? mapId;

  const MapCreateScreen({super.key, this.mapId});

  @override
  ConsumerState<MapCreateScreen> createState() => _MapCreateScreenState();
}

class _MapCreateScreenState extends ConsumerState<MapCreateScreen> {
  final _formKey = GlobalKey<FormState>();
  final _titleCtrl = TextEditingController();
  final _descCtrl = TextEditingController();
  final _tagsCtrl = TextEditingController();
  String _visibility = 'PUBLIC';
  bool _loading = false;
  bool _initializedEditForm = false;

  XFile? _coverImage;
  Uint8List? _coverImageBytes;

  bool get _isEditMode => widget.mapId != null;

  @override
  void dispose() {
    _titleCtrl.dispose();
    _descCtrl.dispose();
    _tagsCtrl.dispose();
    super.dispose();
  }

  Future<void> _pickCoverImage() async {
    final picker = ImagePicker();
    final image = await picker.pickImage(
      source: ImageSource.gallery,
      imageQuality: 85,
      maxWidth: 1600,
    );
    if (image == null) return;
    final bytes = await image.readAsBytes();
    setState(() {
      _coverImage = image;
      _coverImageBytes = bytes;
    });
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() => _loading = true);

    final tags = _tagsCtrl.text
        .split(',')
        .map((tag) => tag.trim())
        .where((tag) => tag.isNotEmpty)
        .toList();

    final title = _titleCtrl.text.trim();
    final description =
        _descCtrl.text.trim().isEmpty ? null : _descCtrl.text.trim();

    try {
      final mapId = widget.mapId;
      final map = mapId == null
          ? await MapsService().createMap(
              title: title,
              description: description,
              tags: tags,
              visibility: _visibility,
            )
          : await MapsService().updateMap(
              mapId,
              title: title,
              description: description,
              tags: tags,
              visibility: _visibility,
            );

      if (_coverImage != null) {
        await _uploadCoverOrWarn(map.id);
      }

      ref.invalidate(mapsListProvider(MapVisibilityFilter.public));
      ref.invalidate(mapsListProvider(MapVisibilityFilter.private));
      if (mapId != null) {
        ref.invalidate(mapDetailProvider(mapId));
      }

      if (mounted) context.pop(true);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(_isEditMode ? userErrorMessage(e, action: '변경 내용을 저장하지 못했어요.') : userErrorMessage(e, action: '지도방을 만들지 못했어요.')),
            backgroundColor: Theme.of(context).colorScheme.error,
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _uploadCoverOrWarn(String mapId) async {
    try {
      await MapsService().uploadCoverImage(mapId, _coverImage!);
      ref.invalidate(mapDetailProvider(mapId));
      ref.invalidate(mapsListProvider(MapVisibilityFilter.public));
      ref.invalidate(mapsListProvider(MapVisibilityFilter.private));
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            _isEditMode
                ? mapCoverUpdatePartialSuccess
                : mapCoverCreatePartialSuccess,
          ),
          backgroundColor: Theme.of(context).colorScheme.tertiary,
        ),
      );
    }
  }

  void _initializeEditForm(MapModel map) {
    if (_initializedEditForm) return;
    _titleCtrl.text = map.title;
    _descCtrl.text = map.description ?? '';
    _tagsCtrl.text = map.tags.join(', ');
    _visibility = map.visibility;
    _initializedEditForm = true;
  }

  @override
  Widget build(BuildContext context) {
    if (_isEditMode) {
      final mapAsync = ref.watch(mapDetailProvider(widget.mapId!));

      return mapAsync.when(
        loading: () => Scaffold(
          appBar: AppBar(title: const Text('지도방 수정')),
          body: const Center(child: CircularProgressIndicator()),
        ),
        error: (error, _) => Scaffold(
          appBar: AppBar(title: const Text('지도방 수정')),
          body: Center(child: Text(userErrorMessage(error, action: '지도방을 불러오지 못했어요.'))),
        ),
        data: (map) {
          _initializeEditForm(map);
          return _buildForm(context, currentCoverImageUrl: map.coverImageUrl);
        },
      );
    }

    return _buildForm(context);
  }

  Widget _buildForm(BuildContext context, {String? currentCoverImageUrl}) {
    return Scaffold(
      appBar: AppBar(title: Text(_isEditMode ? '지도방 수정' : '지도방 만들기')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // 커버 이미지 선택
              Semantics(button: true, label: '표지 사진 선택', child: Tooltip(message: '표지 사진 선택', child: InkWell(
                onTap: _loading ? null : _pickCoverImage,
                child: Container(
                  height: 160,
                  width: double.infinity,
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.surfaceContainerLow,
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(color: Theme.of(context).colorScheme.outlineVariant),
                  ),
                  clipBehavior: Clip.antiAlias,
                  child: _coverImageBytes != null
                      ? Stack(
                          fit: StackFit.expand,
                          children: [
                            Image.memory(_coverImageBytes!, fit: BoxFit.cover),
                            Positioned(
                              right: 8,
                              top: 8,
                              child: Semantics(button: true, label: '선택한 표지 사진 지우기', child: Tooltip(message: '선택한 표지 사진 지우기', child: InkWell(
                                onTap: () => setState(() {
                                  _coverImage = null;
                                  _coverImageBytes = null;
                                }),
                                child: Container(
                                  padding: const EdgeInsets.all(4),
                                  decoration: BoxDecoration(
                                    color: Colors.black54,
                                    borderRadius: BorderRadius.circular(20),
                                  ),
                                  child: const Icon(Icons.close, color: Colors.white, size: 16),
                                ),
                              ))),
                            ),
                          ],
                        )
                      : currentCoverImageUrl != null &&
                              currentCoverImageUrl.isNotEmpty
                          ? Stack(
                              fit: StackFit.expand,
                              children: [
                                Image.network(
                                  ApiConstants.imageUrl(currentCoverImageUrl),
                                  fit: BoxFit.cover,
                                  errorBuilder: (_, __, ___) => Column(
                                    mainAxisAlignment: MainAxisAlignment.center,
                                    children: [
                                      Icon(
                                        Icons.image_not_supported_outlined,
                                        size: 40,
                                        color: Theme.of(context).colorScheme.outline,
                                      ),
                                      const SizedBox(height: 8),
                                      Text(
                                        '표지 사진을 불러오지 못했어요. 다시 선택해 주세요.',
                                        style: TextStyle(
                                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                                          fontSize: 13,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                                Positioned(
                                  left: 12,
                                  bottom: 12,
                                  child: DecoratedBox(
                                    decoration: BoxDecoration(
                                      color: Colors.black54,
                                      borderRadius: BorderRadius.circular(8),
                                    ),
                                    child: Padding(
                                      padding: const EdgeInsets.symmetric(
                                        horizontal: 8,
                                        vertical: 4,
                                      ),
                                      child: Text(
                                        '현재 표지 사진',
                                        style: TextStyle(
                                          color: Colors.white,
                                          fontSize: Theme.of(context).textTheme.bodySmall!.fontSize,
                                        ),
                                      ),
                                    ),
                                  ),
                                ),
                              ],
                            )
                      : Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Icon(Icons.add_photo_alternate_outlined,
                                size: 40, color: Theme.of(context).colorScheme.outline),
                            const SizedBox(height: 8),
                            Text(
                              _isEditMode ? '표지 사진 교체 (선택)' : '표지 사진 추가 (선택)',
                              style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant, fontSize: 13),
                            ),
                          ],
                        ),
                ),
              ))),
              const SizedBox(height: 20),
              TextFormField(
                controller: _titleCtrl,
                decoration: const InputDecoration(
                  labelText: '지도방 이름 *',
                  border: OutlineInputBorder(),
                ),
                validator: (v) =>
                    v == null || v.trim().isEmpty ? '지도방 이름을 입력해 주세요' : null,
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: _descCtrl,
                maxLines: 3,
                decoration: const InputDecoration(
                  labelText: '설명 (선택)',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 16),
              TextFormField(
                controller: _tagsCtrl,
                decoration: const InputDecoration(
                  labelText: '태그 (선택, 쉼표로 구분)',
                  hintText: '예: 맛집, 데이트, 카페',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 20),
              const Text('공개 설정', style: TextStyle(fontWeight: FontWeight.w600)),
              const SizedBox(height: 8),
              SegmentedButton<String>(
                segments: const [
                  ButtonSegment(
                    value: 'PUBLIC',
                    label: Text('공개'),
                    icon: Icon(Icons.public),
                  ),
                  ButtonSegment(
                    value: 'PRIVATE',
                    label: Text('비공개'),
                    icon: Icon(Icons.lock),
                  ),
                ],
                selected: {_visibility},
                onSelectionChanged: (v) =>
                    setState(() => _visibility = v.first),
              ),
              const SizedBox(height: 8),
              Text(_visibility == 'PUBLIC'
                ? 'LOCO에 로그인한 누구나 이 지도방과 장소를 볼 수 있어요.'
                : '참여한 사람만 지도방과 장소를 볼 수 있어요. 초대 코드를 받은 사람은 참여할 수 있어요.',
                style: Theme.of(context).textTheme.bodyMedium),
              const SizedBox(height: 32),
              FilledButton(
                onPressed: _loading ? null : _submit,
                style: FilledButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  backgroundColor: Theme.of(context).colorScheme.primary,
                ),
                child: _loading
                    ? const SizedBox(
                        height: 20,
                        width: 20,
                        child: CircularProgressIndicator(
                            color: Colors.white, strokeWidth: 2))
                    : Text(
                        _isEditMode ? '수정 저장' : '지도방 만들기',
                        style: const TextStyle(fontSize: 16),
                      ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
