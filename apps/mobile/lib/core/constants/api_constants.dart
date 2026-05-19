import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter/foundation.dart';

class ApiConstants {
  ApiConstants._();

  static String get baseUrl {
    const dartDefineUrl = String.fromEnvironment('API_BASE_URL');
    if (dartDefineUrl.isNotEmpty) {
      return _validateReleaseUrl(
        dartDefineUrl,
        name: 'API_BASE_URL',
        mustEndWithApi: true,
      );
    }

    if (kReleaseMode) {
      throw StateError(
        'API_BASE_URL must be provided with --dart-define for release builds.',
      );
    }

    final envUrl = _platformEnv(
      webKey: 'API_BASE_URL_WEB',
      androidKey: 'API_BASE_URL_ANDROID',
      fallbackKey: 'API_BASE_URL',
    );
    if (envUrl != null && envUrl.isNotEmpty) return envUrl;

    return kIsWeb ? 'http://localhost:3000/api' : 'http://10.0.2.2:3000/api';
  }

  static String get serverUrl {
    const dartDefineUrl = String.fromEnvironment('SERVER_URL');
    if (dartDefineUrl.isNotEmpty) {
      return _validateReleaseUrl(dartDefineUrl, name: 'SERVER_URL');
    }

    if (kReleaseMode) {
      throw StateError(
        'SERVER_URL must be provided with --dart-define for release builds.',
      );
    }

    final envUrl = _platformEnv(
      webKey: 'SERVER_URL_WEB',
      androidKey: 'SERVER_URL_ANDROID',
      fallbackKey: 'SERVER_URL',
    );
    if (envUrl != null && envUrl.isNotEmpty) return envUrl;

    return kIsWeb ? 'http://localhost:3000' : 'http://10.0.2.2:3000';
  }

  static String? _platformEnv({
    required String webKey,
    required String androidKey,
    required String fallbackKey,
  }) {
    if (kIsWeb) {
      final webValue = dotenv.maybeGet(webKey);
      if (webValue != null && webValue.isNotEmpty) return webValue;
    }

    if (defaultTargetPlatform == TargetPlatform.android) {
      final androidValue = dotenv.maybeGet(androidKey);
      if (androidValue != null && androidValue.isNotEmpty) return androidValue;
    }

    return dotenv.maybeGet(fallbackKey);
  }

  static String imageUrl(String relativePath) => '$serverUrl$relativePath';

  static String _validateReleaseUrl(
    String url, {
    required String name,
    bool mustEndWithApi = false,
  }) {
    if (!kReleaseMode) return url;

    final parsed = Uri.tryParse(url);
    final host = parsed?.host.toLowerCase();
    final isLocal = host == 'localhost' ||
        host == '127.0.0.1' ||
        host == '10.0.2.2' ||
        host == '0.0.0.0';

    if (parsed == null || !parsed.hasScheme || !parsed.hasAuthority) {
      throw StateError('$name must be an absolute URL in release builds.');
    }
    if (parsed.scheme != 'https') {
      throw StateError('$name must use https in release builds.');
    }
    if (isLocal) {
      throw StateError('$name must not point to a local development host.');
    }
    if (mustEndWithApi && !parsed.path.endsWith('/api')) {
      throw StateError('$name must include the /api path.');
    }

    return url;
  }

  static const String register = '/auth/register';
  static const String login = '/auth/login';
  static const String me = '/users/me';
  static const String maps = '/maps';
  static const String favorites = '/favorites';
  static const String feedback = '/feedback';
  static const String reports = '/reports';
  static const String placeSearch = '/places/search';

  static String mapDetail(String mapId) => '/maps/$mapId';
  static String mapPlaces(String mapId) => '/maps/$mapId/places';
  static String placeDetail(String placeId) => '/places/$placeId';
  static String favoriteDetail(String favoriteId) => '/favorites/$favoriteId';
}
