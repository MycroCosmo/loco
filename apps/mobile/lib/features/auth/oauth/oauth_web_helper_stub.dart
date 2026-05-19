String createOAuthState(String provider) {
  throw UnsupportedError('간편 로그인은 현재 웹에서만 지원됩니다.');
}

String? readOAuthState(String provider) => null;

void clearOAuthState(String provider) {}

void openOAuthUrl(String url) {
  throw UnsupportedError('간편 로그인은 현재 웹에서만 지원됩니다.');
}
