import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import '../constants/api_constants.dart';
import 'web_token_storage_stub.dart'
    if (dart.library.html) 'web_token_storage_web.dart';

class DioClient {
  static final DioClient _instance = DioClient._internal();
  factory DioClient() => _instance;
  DioClient._internal();

  static const _storage = FlutterSecureStorage();
  static const _tokenKey = 'access_token';
  static const _refreshTokenKey = 'refresh_token';
  static String? _memoryToken;
  static String? _memoryRefreshToken;
  static Future<void> Function()? _unauthorizedHandler;
  static bool _handlingUnauthorized = false;
  static Future<bool>? _refreshFuture;

  late final Dio _dio = Dio(
    BaseOptions(
      baseUrl: ApiConstants.baseUrl,
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 10),
      headers: {'Content-Type': 'application/json'},
    ),
  )..interceptors.addAll([
      const _AuthInterceptor(),
      LogInterceptor(
        requestBody: !kReleaseMode,
        responseBody: !kReleaseMode,
      ),
    ]);

  Dio get dio => _dio;

  static Future<void> saveTokens({
    required String accessToken,
    required String refreshToken,
  }) async {
    _memoryToken = accessToken;
    _memoryRefreshToken = refreshToken;
    await saveWebToken(accessToken);
    await saveWebRefreshToken(refreshToken);
    await _storage.write(key: _tokenKey, value: accessToken);
    await _storage.write(key: _refreshTokenKey, value: refreshToken);
    _instance._dio.options.headers['Authorization'] = 'Bearer $accessToken';
  }

  static Future<String?> getToken() async {
    final memoryToken = _memoryToken;
    if (memoryToken != null && memoryToken.isNotEmpty) return memoryToken;

    final storedToken = await _storage.read(key: _tokenKey);
    if (storedToken != null && storedToken.isNotEmpty) {
      _memoryToken = storedToken;
      return storedToken;
    }

    final webToken = readWebToken();
    _memoryToken = webToken;
    return webToken;
  }

  static Future<void> clearToken() async {
    _memoryToken = null;
    _memoryRefreshToken = null;
    await clearWebToken();
    await clearWebRefreshToken();
    await _storage.delete(key: _tokenKey);
    await _storage.delete(key: _refreshTokenKey);
    _instance._dio.options.headers.remove('Authorization');
  }

  static Future<String?> getRefreshToken() async {
    final memoryToken = _memoryRefreshToken;
    if (memoryToken != null && memoryToken.isNotEmpty) return memoryToken;

    final storedToken = await _storage.read(key: _refreshTokenKey);
    if (storedToken != null && storedToken.isNotEmpty) {
      _memoryRefreshToken = storedToken;
      return storedToken;
    }

    final webToken = readWebRefreshToken();
    _memoryRefreshToken = webToken;
    return webToken;
  }

  static void setUnauthorizedHandler(Future<void> Function()? handler) {
    _unauthorizedHandler = handler;
  }

  static Future<void> handleUnauthorized() async {
    await clearToken();

    final handler = _unauthorizedHandler;
    if (handler == null || _handlingUnauthorized) return;

    _handlingUnauthorized = true;
    try {
      await handler();
    } finally {
      _handlingUnauthorized = false;
    }
  }

  static Future<bool> refreshTokens() {
    return _refreshFuture ??= _refreshTokensInternal().whenComplete(
      () => _refreshFuture = null,
    );
  }

  static Future<bool> _refreshTokensInternal() async {
    final refreshToken = await getRefreshToken();
    if (refreshToken == null || refreshToken.isEmpty) return false;

    try {
      final rawDio = Dio(
        BaseOptions(
          baseUrl: ApiConstants.baseUrl,
          connectTimeout: const Duration(seconds: 10),
          receiveTimeout: const Duration(seconds: 10),
          headers: {'Content-Type': 'application/json'},
        ),
      );
      final response = await rawDio.post(
        '/auth/refresh',
        data: {'refreshToken': refreshToken},
      );
      final data = response.data as Map<String, dynamic>;
      await saveTokens(
        accessToken: data['accessToken'] as String,
        refreshToken: data['refreshToken'] as String,
      );
      return true;
    } catch (_) {
      await clearToken();
      return false;
    }
  }
}

class _AuthInterceptor extends Interceptor {
  const _AuthInterceptor();

  @override
  Future<void> onRequest(
    RequestOptions options,
    RequestInterceptorHandler handler,
  ) async {
    options.headers.remove('Authorization');
    final token = await DioClient.getToken();
    if (token != null) {
      options.headers['Authorization'] = 'Bearer $token';
    }
    if (kDebugMode) {
      debugPrint(
        '[DioAuth] ${options.method} ${options.uri} auth=${token != null}',
      );
    }
    handler.next(options);
  }

  @override
  Future<void> onError(
    DioException err,
    ErrorInterceptorHandler handler,
  ) async {
    final options = err.requestOptions;
    if (err.response?.statusCode == 401 &&
        !_isAuthEndpoint(options.path) &&
        options.extra['authRetried'] != true) {
      final refreshed = await DioClient.refreshTokens();
      if (refreshed) {
        try {
          final token = await DioClient.getToken();
          final retryOptions = Options(
            method: options.method,
            headers: {
              ...options.headers,
              if (token != null) 'Authorization': 'Bearer $token',
            },
            responseType: options.responseType,
            contentType: options.contentType,
            followRedirects: options.followRedirects,
            receiveDataWhenStatusError: options.receiveDataWhenStatusError,
            extra: {...options.extra, 'authRetried': true},
          );
          final response = await DioClient().dio.request<dynamic>(
            options.path,
            data: options.data,
            queryParameters: options.queryParameters,
            options: retryOptions,
            cancelToken: options.cancelToken,
            onReceiveProgress: options.onReceiveProgress,
            onSendProgress: options.onSendProgress,
          );
          handler.resolve(response);
          return;
        } catch (_) {
          await DioClient.handleUnauthorized();
        }
      } else {
        await DioClient.handleUnauthorized();
      }
    }
    handler.next(err);
  }

  bool _isAuthEndpoint(String path) {
    return path.contains('/auth/login') ||
        path.contains('/auth/register') ||
        path.contains('/auth/oauth') ||
        path.contains('/auth/refresh') ||
        path.contains('/auth/logout');
  }
}
