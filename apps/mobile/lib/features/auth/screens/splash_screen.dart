import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../providers/auth_provider.dart';

class SplashScreen extends ConsumerWidget {
  const SplashScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    ref.listen<AsyncValue<bool>>(authStateProvider, (_, state) {
      state.whenData((isLoggedIn) {
        if (isLoggedIn) {
          context.go('/maps');
        } else {
          context.go('/login');
        }
      });
    });

    return Scaffold(
      backgroundColor: const Color(0xFFFFFFFF),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: const [
            Icon(Icons.map_rounded, size: 80, color: Color(0xFF2563EB)),
            SizedBox(height: 16),
            Text(
              'LOCO',
              style: TextStyle(
                fontSize: 42,
                fontWeight: FontWeight.bold,
                color: Color(0xFF2563EB),
                letterSpacing: 4,
              ),
            ),
            SizedBox(height: 8),
            Text(
              'Local Collection',
              style: TextStyle(fontSize: 16, color: Color(0xFF6B7280)),
            ),
            SizedBox(height: 48),
            CircularProgressIndicator(color: Color(0xFF2563EB)),
          ],
        ),
      ),
    );
  }
}
