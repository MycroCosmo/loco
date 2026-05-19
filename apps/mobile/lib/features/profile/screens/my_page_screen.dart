import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../../core/constants/api_constants.dart';
import '../../auth/providers/auth_provider.dart';
import '../../feedback/widgets/feedback_modal.dart';
import '../../notifications/notification_service.dart';
import '../providers/user_provider.dart';
import '../services/user_service.dart';

class MyPageScreen extends ConsumerStatefulWidget {
  const MyPageScreen({super.key});

  @override
  ConsumerState<MyPageScreen> createState() => _MyPageScreenState();
}

class _MyPageScreenState extends ConsumerState<MyPageScreen> {
  bool _notificationsEnabled = true;

  @override
  void initState() {
    super.initState();
    _loadNotificationSetting();
  }

  Future<void> _loadNotificationSetting() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      _notificationsEnabled = prefs.getBool('notif_enabled') ?? true;
    });
  }

  Future<void> _toggleNotifications(bool value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('notif_enabled', value);
    setState(() => _notificationsEnabled = value);
    if (value) {
      await NotificationService.instance.init();
    } else {
      await NotificationService.instance.deleteToken();
    }
  }

  @override
  Widget build(BuildContext context) {
    final userAsync = ref.watch(userProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('마이페이지')),
      body: userAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => Center(child: Text('불러오기 실패: $error')),
        data: (user) {
          final profileImageUrl = user.profileImageUrl;
          final ImageProvider? profileImage =
              profileImageUrl == null || profileImageUrl.isEmpty
                  ? null
                  : NetworkImage(
                      profileImageUrl.startsWith('/uploads/')
                          ? ApiConstants.imageUrl(profileImageUrl)
                          : profileImageUrl,
                    );

          return ListView(
            padding: const EdgeInsets.all(24),
            children: [
              Card(
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Padding(
                  padding: const EdgeInsets.all(20),
                  child: Row(
                    children: [
                      CircleAvatar(
                        radius: 36,
                        backgroundColor:
                            const Color(0xFF2563EB).withOpacity(0.1),
                        backgroundImage: profileImage,
                        child: profileImage == null
                            ? Text(
                                user.nickname.isNotEmpty
                                    ? user.nickname[0].toUpperCase()
                                    : '?',
                                style: const TextStyle(
                                  fontSize: 28,
                                  fontWeight: FontWeight.bold,
                                  color: Color(0xFF2563EB),
                                ),
                              )
                            : null,
                      ),
                      const SizedBox(width: 16),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              user.nickname,
                              style: const TextStyle(
                                fontSize: 18,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              user.email,
                              style: const TextStyle(
                                color: Colors.grey,
                                fontSize: 13,
                              ),
                            ),
                          ],
                        ),
                      ),
                      IconButton(
                        icon: const Icon(Icons.edit_outlined),
                        tooltip: '프로필 수정',
                        onPressed: () => context.push('/profile/edit'),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 24),
              _MenuSection(
                title: '내 지도방',
                children: [
                  _MenuTile(
                    icon: Icons.map_outlined,
                    title: '만든 지도방',
                    onTap: () => context.push('/profile/maps/owned'),
                  ),
                  _MenuTile(
                    icon: Icons.groups_outlined,
                    title: '참여한 지도방',
                    onTap: () => context.push('/profile/maps/joined'),
                  ),
                ],
              ),
              _MenuSection(
                title: '내 기록',
                children: [
                  _MenuTile(
                    icon: Icons.place_outlined,
                    title: '등록한 장소',
                    onTap: () => context.push('/profile/places'),
                  ),
                  _MenuTile(
                    icon: Icons.rate_review_outlined,
                    title: '작성한 리뷰',
                    onTap: () => context.push('/profile/reviews'),
                  ),
                ],
              ),
              _MenuSection(
                title: '저장한 항목',
                children: [
                  _MenuTile(
                    icon: Icons.bookmark_outline,
                    title: '즐겨찾기',
                    onTap: () => context.push('/favorites'),
                  ),
                ],
              ),
              _MenuSection(
                title: '알림',
                children: [
                  SwitchListTile(
                    secondary: const Icon(Icons.notifications_outlined),
                    title: const Text('푸시 알림'),
                    value: _notificationsEnabled,
                    onChanged: _toggleNotifications,
                  ),
                ],
              ),
              _MenuSection(
                title: '도움말',
                children: [
                  _MenuTile(
                    icon: Icons.feedback_outlined,
                    title: '피드백 보내기',
                    onTap: () => FeedbackModal.show(context),
                  ),
                ],
              ),
              _MenuSection(
                title: '계정',
                children: [
                  _MenuTile(
                    icon: Icons.logout,
                    iconColor: Colors.orange,
                    title: '로그아웃',
                    titleColor: Colors.orange,
                    onTap: () async {
                      await ref.read(authStateProvider.notifier).logout();
                      if (context.mounted) context.go('/login');
                    },
                  ),
                  _MenuTile(
                    icon: Icons.delete_outline,
                    iconColor: Colors.red,
                    title: '회원 탈퇴',
                    titleColor: Colors.red,
                    onTap: () async {
                      final confirm = await showDialog<bool>(
                        context: context,
                        builder: (_) => AlertDialog(
                          title: const Text('회원 탈퇴'),
                          content: const Text(
                            '정말 탈퇴하시겠어요? 이 작업은 되돌릴 수 없습니다.',
                          ),
                          actions: [
                            TextButton(
                              onPressed: () => Navigator.pop(context, false),
                              child: const Text('취소'),
                            ),
                            TextButton(
                              onPressed: () => Navigator.pop(context, true),
                              child: const Text(
                                '탈퇴',
                                style: TextStyle(color: Colors.red),
                              ),
                            ),
                          ],
                        ),
                      );
                      if (confirm == true && context.mounted) {
                        await UserService().deleteAccount();
                        if (context.mounted) context.go('/login');
                      }
                    },
                  ),
                ],
              ),
            ],
          );
        },
      ),
    );
  }
}

class _MenuSection extends StatelessWidget {
  final String title;
  final List<Widget> children;

  const _MenuSection({
    required this.title,
    required this.children,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.only(left: 4, bottom: 6),
            child: Text(
              title,
              style: Theme.of(context).textTheme.labelLarge?.copyWith(
                    color: Colors.grey[600],
                    fontWeight: FontWeight.w800,
                  ),
            ),
          ),
          Card(
            margin: EdgeInsets.zero,
            child: Column(children: children),
          ),
        ],
      ),
    );
  }
}

class _MenuTile extends StatelessWidget {
  final IconData icon;
  final String title;
  final VoidCallback onTap;
  final Color? iconColor;
  final Color? titleColor;

  const _MenuTile({
    required this.icon,
    required this.title,
    required this.onTap,
    this.iconColor,
    this.titleColor,
  });

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: Icon(icon, color: iconColor),
      title: Text(title, style: TextStyle(color: titleColor)),
      trailing: const Icon(Icons.chevron_right),
      onTap: onTap,
    );
  }
}
