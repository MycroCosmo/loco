import 'dart:async';

import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../../core/network/dio_client.dart';

/// 백그라운드 메시지 핸들러 — top-level function 필수
@pragma('vm:entry-point')
Future<void> _firebaseMessagingBackgroundHandler(RemoteMessage message) async {
  await Firebase.initializeApp();
}

class NotificationService {
  NotificationService({Future<FirebaseMessaging?> Function()? connect})
      : _connect = connect ?? _connectFirebase;

  static final NotificationService instance = NotificationService();
  final Future<FirebaseMessaging?> Function() _connect;
  FirebaseMessaging? _messaging;
  StreamSubscription<String>? _tokenSubscription;
  StreamSubscription<RemoteMessage>? _messageSubscription;
  Future<void> _pending = Future.value();
  int _generation = 0;

  static Future<FirebaseMessaging?> _connectFirebase() async {
    if (kIsWeb) return null;
    await Firebase.initializeApp();
    FirebaseMessaging.onBackgroundMessage(_firebaseMessagingBackgroundHandler);
    return FirebaseMessaging.instance;
  }

  Future<bool> _hasSession() async =>
      (await DioClient.getToken())?.isNotEmpty == true ||
      (await DioClient.getRefreshToken())?.isNotEmpty == true;

  Future<bool> _canRegister(int generation) async {
    final prefs = await SharedPreferences.getInstance();
    final hasSession = await _hasSession();
    return generation == _generation &&
        hasSession &&
        (prefs.getBool('notif_enabled') ?? true);
  }

  Future<void> _enqueue(Future<void> Function() operation) {
    // 마지막 등록 뒤에 해제를 보내 늦게 끝난 등록이 로그아웃을 덮지 않게 한다.
    _pending = _pending.then((_) => operation()).catchError((Object error) {
      debugPrint('[FCM] operation failed: ${error.runtimeType}');
    });
    return _pending;
  }

  Future<void> init() {
    final generation = _generation;
    return _enqueue(() async {
      if (!await _canRegister(generation)) return;
      final messaging = _messaging ??= await _connect();
      if (messaging == null || generation != _generation) return;
      final settings = await messaging.requestPermission(
        alert: true,
        badge: true,
        sound: true,
      );
      if (generation != _generation) return;
      if (settings.authorizationStatus != AuthorizationStatus.authorized &&
          settings.authorizationStatus != AuthorizationStatus.provisional) {
        _generation++;
        _cancelSubscriptions();
        return;
      }
      _tokenSubscription ??= messaging.onTokenRefresh.listen((token) {
        unawaited(_enqueue(() => _uploadToken(token, generation)));
      }, onError: (Object error) {
        debugPrint('[FCM] token refresh failed: ${error.runtimeType}');
      });
      _messageSubscription ??= FirebaseMessaging.onMessage.listen((message) {
        debugPrint('[FCM] foreground message received');
      });
      final token = await messaging.getToken();
      if (token != null) await _uploadToken(token, generation);
    });
  }

  Future<void> _uploadToken(String token, int generation) async {
    if (token.isEmpty || !await _canRegister(generation)) return;
    await DioClient().dio.put(
      '/users/me/fcm-token',
      data: {'fcmToken': token},
    );
  }

  void _cancelSubscriptions() {
    unawaited(_tokenSubscription?.cancel());
    unawaited(_messageSubscription?.cancel());
    _tokenSubscription = null;
    _messageSubscription = null;
  }

  Future<void> deleteToken({bool clearServer = true}) {
    _generation++;
    _cancelSubscriptions();
    // 웹은 FCM을 등록하지 않으므로 같은 계정의 휴대폰 토큰도 지우지 않는다.
    if (kIsWeb) return Future.value();
    return _enqueue(() async {
      if (clearServer && await _hasSession()) {
        try {
          await DioClient().dio.put(
            '/users/me/fcm-token',
            data: {'fcmToken': null},
          );
        } catch (error) {
          debugPrint('[FCM] server unregister failed: ${error.runtimeType}');
        }
      }
      try {
        final messaging = _messaging ??= await _connect();
        await messaging?.deleteToken();
      } catch (error) {
        debugPrint('[FCM] device unregister failed: ${error.runtimeType}');
      }
    });
  }
}
