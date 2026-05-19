// ignore: avoid_web_libraries_in_flutter
import 'dart:html' as html;

const _tokenKey = 'loco_access_token';
const _refreshTokenKey = 'loco_refresh_token';

Future<void> saveWebToken(String token) async {
  html.window.localStorage[_tokenKey] = token;
}

String? readWebToken() {
  return html.window.localStorage[_tokenKey];
}

Future<void> clearWebToken() async {
  html.window.localStorage.remove(_tokenKey);
}

Future<void> saveWebRefreshToken(String token) async {
  html.window.localStorage[_refreshTokenKey] = token;
}

String? readWebRefreshToken() {
  return html.window.localStorage[_refreshTokenKey];
}

Future<void> clearWebRefreshToken() async {
  html.window.localStorage.remove(_refreshTokenKey);
}
