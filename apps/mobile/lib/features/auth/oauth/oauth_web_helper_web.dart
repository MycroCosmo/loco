// ignore: avoid_web_libraries_in_flutter
import 'dart:html' as html;
import 'dart:math';

String createOAuthState(String provider) {
  final random = Random.secure();
  final values = List<int>.generate(16, (_) => random.nextInt(256));
  final state = values.map((value) => value.toRadixString(16).padLeft(2, '0')).join();
  html.window.localStorage[_stateKey(provider)] = state;
  html.window.sessionStorage[_stateKey(provider)] = state;
  return state;
}

String? readOAuthState(String provider) {
  return html.window.sessionStorage[_stateKey(provider)] ??
      html.window.localStorage[_stateKey(provider)];
}

void clearOAuthState(String provider) {
  html.window.localStorage.remove(_stateKey(provider));
  html.window.sessionStorage.remove(_stateKey(provider));
}

void openOAuthUrl(String url) {
  html.window.location.assign(url);
}

String _stateKey(String provider) => 'loco_oauth_state_$provider';
