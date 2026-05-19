import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';

import '../../../core/constants/api_constants.dart';
import '../../../shared/models/kakao_place_result.dart';
import '../../../shared/models/place_model.dart';
import '../../../shared/models/map_marker_model.dart';
import '../../../shared/widgets/kakao_map_widget.dart';
import '../services/kakao_place_search_service.dart';
import '../services/places_service.dart';

class PlaceAddScreen extends ConsumerStatefulWidget {
  final String mapId;
  final PlaceModel? initialPlace;

  const PlaceAddScreen({
    super.key,
    required this.mapId,
    this.initialPlace,
  });

  @override
  ConsumerState<PlaceAddScreen> createState() => _PlaceAddScreenState();
}

class _PlaceAddScreenState extends ConsumerState<PlaceAddScreen> {
  final _formKey = GlobalKey<FormState>();
  final _searchCtrl = TextEditingController();
  final _nameCtrl = TextEditingController();
  final _memoCtrl = TextEditingController();
  final _tagCtrl = TextEditingController();

  List<KakaoPlaceResult> _searchResults = [];
  KakaoPlaceResult? _selectedResult;
  bool _searching = false;
  bool _submitting = false;

  final List<String> _tags = [];
  final List<XFile> _images = [];
  final List<String> _existingImageUrls = [];
  final Map<String, Uint8List> _imageBytesCache = {};

  bool get _isEditMode => widget.initialPlace != null;

  @override
  void initState() {
    super.initState();
    final place = widget.initialPlace;
    if (place != null) {
      _nameCtrl.text = place.name;
      _memoCtrl.text = place.memo ?? '';
      _searchCtrl.text = place.name;
      _tags.addAll(place.tags);
      _existingImageUrls.addAll(place.imageUrls);
    }
  }

  @override
  void dispose() {
    _searchCtrl.dispose();
    _nameCtrl.dispose();
    _memoCtrl.dispose();
    _tagCtrl.dispose();
    super.dispose();
  }

  Future<void> _search() async {
    final query = _searchCtrl.text.trim();
    if (query.isEmpty) return;

    setState(() {
      _searching = true;
      _searchResults = [];
      _selectedResult = null;
    });

    try {
      final results = await KakaoPlaceSearchService().search(query);
      if (mounted) setState(() => _searchResults = results);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('검색 실패: $e'), backgroundColor: Colors.red),
        );
      }
    } finally {
      if (mounted) setState(() => _searching = false);
    }
  }

  void _selectResult(KakaoPlaceResult result) {
    setState(() {
      _selectedResult = result;
      _nameCtrl.text = result.placeName;
      _searchResults = [];
      _searchCtrl.text = result.placeName;
    });
  }

  void _commitTagInput() {
    final rawTags = _tagCtrl.text
        .split(RegExp(r'[\s,]+'))
        .map((tag) => tag.trim())
        .where((tag) => tag.isNotEmpty);

    setState(() {
      for (final tag in rawTags) {
        if (!_tags.contains(tag)) {
          _tags.add(tag);
        }
      }
      _tagCtrl.clear();
    });
  }

  void _removeTag(String tag) {
    setState(() => _tags.remove(tag));
  }

  Future<void> _pickImage() async {
    if (_existingImageUrls.length + _images.length >= 3) return;

    final picker = ImagePicker();
    final image = await picker.pickImage(
      source: ImageSource.gallery,
      imageQuality: 80,
      maxWidth: 1600,
    );

    if (image == null) return;

    final bytes = await image.readAsBytes();
    setState(() {
      _images.add(image);
      _imageBytesCache[image.path] = bytes;
    });
  }

  void _removeImage(int index) {
    setState(() {
      _imageBytesCache.remove(_images[index].path);
      _images.removeAt(index);
    });
  }

  Future<void> _removeExistingImage(int index) async {
    final imageUrl = _existingImageUrls[index];
    setState(() => _submitting = true);
    try {
      await PlacesService().deletePlaceImage(widget.initialPlace!.id, imageUrl);
      if (mounted) {
        setState(() => _existingImageUrls.removeAt(index));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('사진 삭제 실패: $e'), backgroundColor: Colors.red),
        );
      }
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  Future<void> _submit() async {
    if (!_isEditMode && _selectedResult == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('장소를 검색해서 선택해주세요.')),
      );
      return;
    }

    if (!_formKey.currentState!.validate()) return;

    if (_tagCtrl.text.trim().isNotEmpty) {
      _commitTagInput();
    }

    setState(() => _submitting = true);

    try {
      if (_isEditMode) {
        await PlacesService().updatePlace(widget.initialPlace!.id, {
          'name': _nameCtrl.text.trim(),
          'memo': _memoCtrl.text.trim().isEmpty ? null : _memoCtrl.text.trim(),
          'tags': List<String>.from(_tags),
        });

        for (final image in _images) {
          await PlacesService().uploadPlaceImage(widget.initialPlace!.id, image);
        }

        if (mounted) context.pop(true);
        return;
      }

      final place = PlaceModel(
        id: '',
        mapId: widget.mapId,
        createdById: '',
        name: _nameCtrl.text.trim(),
        address: _selectedResult!.addressName,
        roadAddress: _selectedResult!.roadAddressName.isNotEmpty
            ? _selectedResult!.roadAddressName
            : null,
        latitude: _selectedResult!.latitude,
        longitude: _selectedResult!.longitude,
        phone: _selectedResult!.phone.isNotEmpty ? _selectedResult!.phone : null,
        category: _selectedResult!.categoryName.isNotEmpty
            ? _selectedResult!.categoryName.split('>').last.trim()
            : null,
        memo: _memoCtrl.text.trim().isEmpty ? null : _memoCtrl.text.trim(),
        tags: List<String>.from(_tags),
        createdAt: DateTime.now(),
      );

      final created = await PlacesService().createPlace(widget.mapId, place);
      var imageUploadFailed = false;

      for (final image in _images) {
        try {
          await PlacesService().uploadPlaceImage(created.id, image);
        } catch (e) {
          imageUploadFailed = true;
          if (mounted) {
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(
                content: Text('장소는 생성됐지만 일부 사진 업로드에 실패했습니다: $e'),
                backgroundColor: Colors.orange,
              ),
            );
          }
          break;
        }
      }

      if (mounted) {
        context.pop(!imageUploadFailed);
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('추가 실패: $e'), backgroundColor: Colors.red),
        );
      }
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  Widget _buildImageThumbnail(int index) {
    final image = _images[index];
    final bytes = _imageBytesCache[image.path];

    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: Stack(
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(8),
            child: bytes != null
                ? Image.memory(bytes, width: 72, height: 72, fit: BoxFit.cover)
                : Container(width: 72, height: 72, color: Colors.grey.shade200),
          ),
          Positioned(
            top: 2,
            right: 2,
            child: GestureDetector(
              onTap: () => _removeImage(index),
              child: Container(
                padding: const EdgeInsets.all(2),
                decoration: BoxDecoration(
                  color: Colors.black54,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: const Icon(Icons.close, color: Colors.white, size: 14),
              ),
            ),
          ),
          if (index == 0)
            Positioned(
              left: 4,
              bottom: 4,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 2),
                decoration: BoxDecoration(
                  color: Colors.black54,
                  borderRadius: BorderRadius.circular(6),
                ),
                child: const Text(
                  '대표',
                  style: TextStyle(color: Colors.white, fontSize: 10),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildExistingImageThumbnail(int index) {
    final imageUrl = _existingImageUrls[index];

    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: Stack(
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(8),
            child: Image.network(
              ApiConstants.imageUrl(imageUrl),
              width: 72,
              height: 72,
              fit: BoxFit.cover,
              errorBuilder: (_, __, ___) => Container(
                width: 72,
                height: 72,
                color: Colors.grey.shade200,
                child: const Icon(Icons.broken_image_outlined),
              ),
            ),
          ),
          Positioned(
            top: 2,
            right: 2,
            child: GestureDetector(
              onTap: _submitting ? null : () => _removeExistingImage(index),
              child: Container(
                padding: const EdgeInsets.all(2),
                decoration: BoxDecoration(
                  color: Colors.black54,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: const Icon(Icons.close, color: Colors.white, size: 14),
              ),
            ),
          ),
          if (index == 0)
            Positioned(
              left: 4,
              bottom: 4,
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 2),
                decoration: BoxDecoration(
                  color: Colors.black54,
                  borderRadius: BorderRadius.circular(6),
                ),
                child: const Text(
                  '대표',
                  style: TextStyle(color: Colors.white, fontSize: 10),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildTagInput() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        TextField(
          controller: _tagCtrl,
          decoration: const InputDecoration(
            labelText: '태그',
            hintText: '입력 후 엔터 또는 스페이스',
            border: OutlineInputBorder(),
            prefixIcon: Icon(Icons.tag),
          ),
          textInputAction: TextInputAction.done,
          onSubmitted: (_) => _commitTagInput(),
          onChanged: (value) {
            if (value.endsWith(' ') || value.endsWith(',')) {
              _commitTagInput();
            }
          },
        ),
        if (_tags.isNotEmpty) ...[
          const SizedBox(height: 8),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: _tags.map((tag) {
              return InputChip(
                label: Text(tag),
                onDeleted: () => _removeTag(tag),
                visualDensity: VisualDensity.compact,
              );
            }).toList(),
          ),
        ],
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(_isEditMode ? '장소 수정' : '장소 추가')),
      body: Column(
        children: [
          SizedBox(
            height: 180,
            child: _isEditMode
                ? KakaoMapWidget(
                    latitude: widget.initialPlace!.latitude,
                    longitude: widget.initialPlace!.longitude,
                    level: 3,
                    markers: [MapMarkerModel.fromPlace(widget.initialPlace!)],
                  )
                : _selectedResult != null
                ? KakaoMapWidget(
                    latitude: _selectedResult!.latitude,
                    longitude: _selectedResult!.longitude,
                    level: 3,
                    markers: [
                      MapMarkerModel(
                        id: '',
                        name: _selectedResult!.placeName,
                        latitude: _selectedResult!.latitude,
                        longitude: _selectedResult!.longitude,
                      ),
                    ],
                  )
                : const ColoredBox(
                    color: Color(0xFFE5E7EB),
                    child: Center(
                      child: Text(
                        '장소를 검색하면\n지도에 표시됩니다.',
                        textAlign: TextAlign.center,
                        style: TextStyle(color: Colors.grey),
                      ),
                    ),
                  ),
          ),
          Expanded(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(20),
              child: Form(
                key: _formKey,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (!_isEditMode) ...[
                      Row(
                        children: [
                          Expanded(
                            child: TextField(
                              controller: _searchCtrl,
                              decoration: const InputDecoration(
                                labelText: '장소 검색',
                                hintText: '예: 종각역 맛집',
                                border: OutlineInputBorder(),
                                prefixIcon: Icon(Icons.search),
                              ),
                              onSubmitted: (_) => _search(),
                            ),
                          ),
                          const SizedBox(width: 8),
                          FilledButton(
                            onPressed: _searching ? null : _search,
                            style: FilledButton.styleFrom(
                              backgroundColor: const Color(0xFF2563EB),
                              padding: const EdgeInsets.symmetric(
                                horizontal: 16,
                                vertical: 16,
                              ),
                            ),
                            child: _searching
                                ? const SizedBox(
                                    width: 18,
                                    height: 18,
                                    child: CircularProgressIndicator(
                                      color: Colors.white,
                                      strokeWidth: 2,
                                    ),
                                  )
                                : const Text('검색'),
                          ),
                        ],
                      ),
                    ],
                    if (!_isEditMode && _searchResults.isNotEmpty) ...[
                      const SizedBox(height: 4),
                      Container(
                        decoration: BoxDecoration(
                          border: Border.all(color: Colors.grey.shade300),
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: ListView.separated(
                          shrinkWrap: true,
                          physics: const NeverScrollableScrollPhysics(),
                          itemCount: _searchResults.length,
                          separatorBuilder: (_, __) => const Divider(height: 1),
                          itemBuilder: (context, index) {
                            final result = _searchResults[index];
                            return ListTile(
                              dense: true,
                              title: Text(
                                result.placeName,
                                style: const TextStyle(
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                              subtitle: Text(
                                result.roadAddressName.isNotEmpty
                                    ? result.roadAddressName
                                    : result.addressName,
                                style: const TextStyle(fontSize: 12),
                              ),
                              trailing: result.categoryName.isNotEmpty
                                  ? Text(
                                      result.categoryName.split('>').last.trim(),
                                      style: const TextStyle(
                                        fontSize: 11,
                                        color: Colors.grey,
                                      ),
                                    )
                                  : null,
                              onTap: () => _selectResult(result),
                            );
                          },
                        ),
                      ),
                    ],
                    if (!_isEditMode && _selectedResult != null) ...[
                      const SizedBox(height: 12),
                      Container(
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: const Color(0xFF2563EB).withOpacity(0.06),
                          borderRadius: BorderRadius.circular(8),
                          border: Border.all(
                            color: const Color(0xFF2563EB).withOpacity(0.3),
                          ),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                const Icon(
                                  Icons.check_circle,
                                  color: Color(0xFF2563EB),
                                  size: 16,
                                ),
                                const SizedBox(width: 6),
                                Expanded(
                                  child: Text(
                                    _selectedResult!.placeName,
                                    style: const TextStyle(
                                      fontWeight: FontWeight.bold,
                                    ),
                                  ),
                                ),
                              ],
                            ),
                            if (_selectedResult!.roadAddressName.isNotEmpty)
                              Padding(
                                padding: const EdgeInsets.only(top: 4, left: 22),
                                child: Text(
                                  _selectedResult!.roadAddressName,
                                  style: const TextStyle(
                                    fontSize: 12,
                                    color: Colors.grey,
                                  ),
                                ),
                              ),
                            if (_selectedResult!.phone.isNotEmpty)
                              Padding(
                                padding: const EdgeInsets.only(top: 2, left: 22),
                                child: Text(
                                  _selectedResult!.phone,
                                  style: const TextStyle(
                                    fontSize: 12,
                                    color: Colors.grey,
                                  ),
                                ),
                              ),
                          ],
                        ),
                      ),
                    ],
                    if (!_isEditMode) const SizedBox(height: 16),
                    TextFormField(
                      controller: _nameCtrl,
                      decoration: const InputDecoration(
                        labelText: '저장할 이름',
                        border: OutlineInputBorder(),
                      ),
                      validator: (value) =>
                          value == null || value.trim().isEmpty
                              ? '이름을 입력하세요'
                              : null,
                    ),
                    const SizedBox(height: 12),
                    TextFormField(
                      controller: _memoCtrl,
                      maxLines: 3,
                      decoration: const InputDecoration(
                        labelText: '메모',
                        hintText: '이 장소에 대한 메모를 남겨보세요.',
                        border: OutlineInputBorder(),
                      ),
                    ),
                    const SizedBox(height: 12),
                    _buildTagInput(),
                    const SizedBox(height: 16),
                    Row(
                      children: [
                        const Text(
                          '사진 첨부',
                          style: TextStyle(fontWeight: FontWeight.w600),
                        ),
                        const SizedBox(width: 6),
                        Text(
                          '(${_existingImageUrls.length + _images.length}/3)',
                          style: const TextStyle(
                            fontSize: 12,
                            color: Colors.grey,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        ...List.generate(
                          _existingImageUrls.length,
                          (index) => _buildExistingImageThumbnail(index),
                        ),
                        ...List.generate(
                          _images.length,
                          (index) => _buildImageThumbnail(index),
                        ),
                        if (_existingImageUrls.length + _images.length < 3)
                          GestureDetector(
                            onTap: _submitting ? null : _pickImage,
                            child: Container(
                              width: 72,
                              height: 72,
                              decoration: BoxDecoration(
                                color: const Color(0xFFF3F4F6),
                                borderRadius: BorderRadius.circular(8),
                                border: Border.all(color: Colors.grey.shade300),
                              ),
                              child: Icon(
                                Icons.add_a_photo_outlined,
                                color: Colors.grey.shade400,
                              ),
                            ),
                          ),
                      ],
                    ),
                    const SizedBox(height: 28),
                    FilledButton(
                      onPressed: _submitting ? null : _submit,
                      style: FilledButton.styleFrom(
                        backgroundColor: const Color(0xFF2563EB),
                        padding: const EdgeInsets.symmetric(vertical: 14),
                      ),
                      child: _submitting
                          ? const SizedBox(
                              height: 20,
                              width: 20,
                              child: CircularProgressIndicator(
                                color: Colors.white,
                                strokeWidth: 2,
                              ),
                            )
                          : Text(
                              _isEditMode ? '수정 저장' : '장소 추가',
                              style: const TextStyle(fontSize: 16),
                            ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
