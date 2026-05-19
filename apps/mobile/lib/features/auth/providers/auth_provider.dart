import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/network/dio_client.dart';
import '../services/auth_service.dart';
import '../../notifications/notification_service.dart';
import 'session_invalidation.dart';

final authServiceProvider = Provider<AuthService>((ref) => AuthService());

final authStateProvider =
    StateNotifierProvider<AuthNotifier, AsyncValue<bool>>(
  (ref) => AuthNotifier(ref, ref.read(authServiceProvider)),
);

class AuthNotifier extends StateNotifier<AsyncValue<bool>> {
  final Ref _ref;
  final AuthService _authService;

  AuthNotifier(this._ref, this._authService) : super(const AsyncValue.loading()) {
    DioClient.setUnauthorizedHandler(_handleUnauthorized);
    _checkAuth();
  }

  Future<void> _checkAuth() async {
    final loggedIn = await _authService.isLoggedIn();
    state = AsyncValue.data(loggedIn);
  }

  Future<void> register(String email, String password, String nickname) async {
    state = const AsyncValue.loading();
    try {
      await _authService.register(email, password, nickname);
      _clearSessionProviders();
      state = const AsyncValue.data(true);
    } catch (e, st) {
      state = AsyncValue.error(e, st);
    }
  }

  Future<void> login(String email, String password) async {
    state = const AsyncValue.loading();
    try {
      await _authService.login(email, password);
      _clearSessionProviders();
      state = const AsyncValue.data(true);
      NotificationService.instance.init();
    } catch (e, st) {
      state = AsyncValue.error(e, st);
    }
  }

  Future<void> oauthLogin({
    required String provider,
    required String code,
    required String redirectUri,
    String? oauthState,
  }) async {
    state = const AsyncValue.loading();
    try {
      await _authService.oauthLogin(
        provider: provider,
        code: code,
        redirectUri: redirectUri,
        state: oauthState,
      );
      _clearSessionProviders();
      state = const AsyncValue.data(true);
    } catch (e, st) {
      state = AsyncValue.error(e, st);
    }
  }

  Future<void> logout() async {
    await _authService.logout();
    _clearSessionProviders();
    state = const AsyncValue.data(false);
  }

  Future<void> _handleUnauthorized() async {
    _clearSessionProviders();
    if (mounted) {
      state = const AsyncValue.data(false);
    }
  }

  void _clearSessionProviders() {
    invalidateSessionProviders(_ref);
  }

  @override
  void dispose() {
    DioClient.setUnauthorizedHandler(null);
    super.dispose();
  }
}
