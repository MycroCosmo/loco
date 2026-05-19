import '../../../core/network/dio_client.dart';
import '../../../core/constants/api_constants.dart';
import '../../notifications/notification_service.dart';

class AuthService {
  final _client = DioClient();

  Future<String> register(String email, String password, String nickname) async {
    final response = await _client.dio.post(
      ApiConstants.register,
      data: {'email': email, 'password': password, 'nickname': nickname},
    );
    final token = await _saveTokenPair(response.data as Map<String, dynamic>);
    return token;
  }

  Future<String> login(String email, String password) async {
    final response = await _client.dio.post(
      ApiConstants.login,
      data: {'email': email, 'password': password},
    );
    final token = await _saveTokenPair(response.data as Map<String, dynamic>);
    return token;
  }

  Future<String> oauthLogin({
    required String provider,
    required String code,
    required String redirectUri,
    String? state,
  }) async {
    final response = await _client.dio.post(
      '/auth/oauth/$provider',
      data: {
        'code': code,
        'redirectUri': redirectUri,
        if (state != null) 'state': state,
      },
    );
    final token = await _saveTokenPair(response.data as Map<String, dynamic>);
    return token;
  }

  Future<void> logout() async {
    // FCM 토큰 먼저 삭제 (조용히 실패해도 됨)
    await NotificationService.instance.deleteToken();

    final refreshToken = await DioClient.getRefreshToken();
    if (refreshToken != null && refreshToken.isNotEmpty) {
      try {
        await _client.dio.post(
          '/auth/logout',
          data: {'refreshToken': refreshToken},
        );
      } catch (_) {
        // Local logout should still complete if the server token is already invalid.
      }
    }
    await DioClient.clearToken();
  }

  Future<bool> isLoggedIn() async {
    final token = await DioClient.getToken();
    final refreshToken = await DioClient.getRefreshToken();
    return token != null || refreshToken != null;
  }

  Future<String> _saveTokenPair(Map<String, dynamic> data) async {
    final accessToken = data['accessToken'] as String;
    final refreshToken = data['refreshToken'] as String;
    await DioClient.saveTokens(
      accessToken: accessToken,
      refreshToken: refreshToken,
    );
    return accessToken;
  }
}
