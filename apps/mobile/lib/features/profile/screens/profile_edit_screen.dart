import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';

import '../../../core/constants/api_constants.dart';
import '../providers/user_provider.dart';
import '../services/user_service.dart';

class ProfileEditScreen extends ConsumerStatefulWidget {
  const ProfileEditScreen({super.key});

  @override
  ConsumerState<ProfileEditScreen> createState() => _ProfileEditScreenState();
}

class _ProfileEditScreenState extends ConsumerState<ProfileEditScreen> {
  final _formKey = GlobalKey<FormState>();
  final _nicknameCtrl = TextEditingController();
  XFile? _selectedImage;
  Uint8List? _selectedImageBytes;
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    final user = ref.read(userProvider).valueOrNull;
    if (user != null) _nicknameCtrl.text = user.nickname;
  }

  @override
  void dispose() {
    _nicknameCtrl.dispose();
    super.dispose();
  }

  Future<void> _pickImage() async {
    final picker = ImagePicker();
    final image = await picker.pickImage(
      source: ImageSource.gallery,
      imageQuality: 85,
      maxWidth: 1200,
    );
    if (image == null) return;

    final bytes = await image.readAsBytes();
    setState(() {
      _selectedImage = image;
      _selectedImageBytes = bytes;
    });
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() => _loading = true);
    try {
      await UserService().updateMe(nickname: _nicknameCtrl.text.trim());
      final selectedImage = _selectedImage;
      if (selectedImage != null) {
        await UserService().uploadProfileImage(selectedImage);
      }

      ref.invalidate(userProvider);
      if (mounted) {
        context.pop();
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('프로필이 수정되었습니다.')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('수정 실패: $e'), backgroundColor: Colors.red),
        );
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  ImageProvider? _profileImageProvider(String? profileImageUrl) {
    final selectedBytes = _selectedImageBytes;
    if (selectedBytes != null) {
      return MemoryImage(selectedBytes);
    }

    if (profileImageUrl == null || profileImageUrl.isEmpty) return null;
    final url = profileImageUrl.startsWith('/uploads/')
        ? ApiConstants.imageUrl(profileImageUrl)
        : profileImageUrl;
    return NetworkImage(url);
  }

  @override
  Widget build(BuildContext context) {
    final user = ref.watch(userProvider).valueOrNull;
    final profileImage = _profileImageProvider(user?.profileImageUrl);

    return Scaffold(
      appBar: AppBar(title: const Text('프로필 수정')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Center(
                child: Stack(
                  children: [
                    CircleAvatar(
                      radius: 48,
                      backgroundColor: const Color(0xFF2563EB).withOpacity(0.1),
                      backgroundImage: profileImage,
                      child: profileImage == null
                          ? Text(
                              (user?.nickname.isNotEmpty ?? false)
                                  ? user!.nickname[0].toUpperCase()
                                  : '?',
                              style: const TextStyle(
                                fontSize: 34,
                                fontWeight: FontWeight.bold,
                                color: Color(0xFF2563EB),
                              ),
                            )
                          : null,
                    ),
                    Positioned(
                      right: 0,
                      bottom: 0,
                      child: IconButton.filled(
                        onPressed: _loading ? null : _pickImage,
                        icon: const Icon(Icons.camera_alt, size: 18),
                        tooltip: '프로필 사진 변경',
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 10),
              Center(
                child: TextButton.icon(
                  onPressed: _loading ? null : _pickImage,
                  icon: const Icon(Icons.image_outlined),
                  label: const Text('사진 선택'),
                ),
              ),
              const SizedBox(height: 24),
              TextFormField(
                controller: _nicknameCtrl,
                decoration: const InputDecoration(
                  labelText: '닉네임',
                  border: OutlineInputBorder(),
                ),
                validator: (value) {
                  final nickname = value?.trim() ?? '';
                  if (nickname.length < 2) return '2자 이상 입력하세요.';
                  if (nickname.length > 30) return '30자 이하로 입력하세요.';
                  return null;
                },
              ),
              const SizedBox(height: 32),
              FilledButton(
                onPressed: _loading ? null : _submit,
                style: FilledButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 14),
                  backgroundColor: const Color(0xFF2563EB),
                ),
                child: _loading
                    ? const SizedBox(
                        height: 20,
                        width: 20,
                        child: CircularProgressIndicator(
                          color: Colors.white,
                          strokeWidth: 2,
                        ),
                      )
                    : const Text('저장', style: TextStyle(fontSize: 16)),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
