import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import '../../core/network/dio_client.dart';

/// 백그라운드 메시지 핸들러 — top-level function 필수
@pragma('vm:entry-point')
Future<void> _firebaseMessagingBackgroundHandler(RemoteMessage message) async {
  await Firebase.initializeApp();
}

class NotificationService {
  NotificationService._();
  static final NotificationService instance = NotificationService._();

  bool _initialized = false;

  Future<void> init() async {
    if (_initialized) return;
    _initialized = true;

    if (kIsWeb) {
      debugPrint('[FCM] skipped on web without FirebaseOptions');
      return;
    }

    try {
      await Firebase.initializeApp();
    } catch (e) {
      debugPrint('[FCM] Firebase init failed: $e');
      return;
    }

    FirebaseMessaging.onBackgroundMessage(_firebaseMessagingBackgroundHandler);

    final messaging = FirebaseMessaging.instance;

    // iOS/Web 권한 요청
    final settings = await messaging.requestPermission(
      alert: true,
      badge: true,
      sound: true,
    );

    if (settings.authorizationStatus == AuthorizationStatus.authorized ||
        settings.authorizationStatus == AuthorizationStatus.provisional) {
      await _registerToken(messaging);
    }

    // 토큰 갱신 시 재등록
    messaging.onTokenRefresh.listen((token) => _uploadToken(token));

    // 포그라운드 메시지 수신 (필요 시 로컬 알림으로 표시)
    FirebaseMessaging.onMessage.listen((message) {
      debugPrint('[FCM] foreground: ${message.notification?.title}');
    });
  }

  Future<void> _registerToken(FirebaseMessaging messaging) async {
    try {
      final token = await messaging.getToken();
      if (token != null) await _uploadToken(token);
    } catch (e) {
      debugPrint('[FCM] getToken failed: $e');
    }
  }

  Future<void> _uploadToken(String token) async {
    try {
      await DioClient().dio.put(
        '/users/me/fcm-token',
        data: {'fcmToken': token},
      );
    } catch (e) {
      debugPrint('[FCM] token upload failed: $e');
    }
  }

  Future<void> deleteToken() async {
    try {
      await FirebaseMessaging.instance.deleteToken();
      await DioClient().dio.put(
        '/users/me/fcm-token',
        data: {'fcmToken': null},
      );
      _initialized = false;
    } catch (e) {
      debugPrint('[FCM] deleteToken failed: $e');
    }
  }
}
