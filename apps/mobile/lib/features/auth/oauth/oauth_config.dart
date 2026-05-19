import 'package:flutter/foundation.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';

import 'oauth_web_helper_stub.dart'
    if (dart.library.html) 'oauth_web_helper_web.dart';

class OAuthConfig {
  OAuthConfig._();

  static String get kakaoClientId {
    const dartDefineValue = String.fromEnvironment('KAKAO_REST_API_KEY');
    return dartDefineValue.isNotEmpty
        ? dartDefineValue
        : dotenv.env['KAKAO_REST_API_KEY'] ?? '';
  }

  static String get naverClientId {
    const dartDefineValue = String.fromEnvironment('NAVER_CLIENT_ID');
    return dartDefineValue.isNotEmpty
        ? dartDefineValue
        : dotenv.env['NAVER_CLIENT_ID'] ?? '';
  }

  static String redirectUri(String provider) {
    if (!kIsWeb) {
      throw UnsupportedError('간편 로그인은 현재 웹에서만 지원됩니다.');
    }
    return '${Uri.base.origin}/oauth/$provider/callback';
  }

  static String authorizeUrl(String provider) {
    final state = createOAuthState(provider);
    final redirect = redirectUri(provider);

    if (provider == 'kakao') {
      if (kakaoClientId.isEmpty) {
        throw StateError('KAKAO_REST_API_KEY가 설정되지 않았습니다.');
      }
      return Uri.https('kauth.kakao.com', '/oauth/authorize', {
        'response_type': 'code',
        'client_id': kakaoClientId,
        'redirect_uri': redirect,
        'state': state,
      }).toString();
    }

    if (provider == 'naver') {
      if (naverClientId.isEmpty) {
        throw StateError('NAVER_CLIENT_ID가 설정되지 않았습니다.');
      }
      return Uri.https('nid.naver.com', '/oauth2.0/authorize', {
        'response_type': 'code',
        'client_id': naverClientId,
        'redirect_uri': redirect,
        'state': state,
      }).toString();
    }

    throw StateError('지원하지 않는 간편 로그인입니다.');
  }

  static void open(String provider) {
    openOAuthUrl(authorizeUrl(provider));
  }

  static bool verifyState(String provider, String? state) {
    final savedState = readOAuthState(provider);
    return savedState != null && state != null && savedState == state;
  }

  static String? savedState(String provider) {
    return readOAuthState(provider);
  }

  static void clearState(String provider) {
    clearOAuthState(provider);
  }
}
