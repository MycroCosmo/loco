import 'dart:async';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:loco_mobile/core/network/dio_client.dart';
import 'package:loco_mobile/features/auth/services/auth_service.dart';
import 'package:loco_mobile/features/notifications/notification_service.dart';

class _Settings extends Fake implements NotificationSettings {
  _Settings(this.authorizationStatus);
  @override
  final AuthorizationStatus authorizationStatus;
}

class _Messaging extends Fake implements FirebaseMessaging {
  final tokens = StreamController<String>.broadcast(sync: true);
  AuthorizationStatus permission = AuthorizationStatus.authorized;
  Completer<String?>? tokenGate;
  final tokenRequested = Completer<void>();
  int deletions = 0;
  bool deleteFails = false;

  @override
  Stream<String> get onTokenRefresh => tokens.stream;

  @override
  Future<String?> getToken({String? vapidKey}) async {
    if (!tokenRequested.isCompleted) tokenRequested.complete();
    return tokenGate == null ? 'device-token' : await tokenGate!.future;
  }

  @override
  Future<void> deleteToken() async {
    deletions++;
    if (deleteFails) throw StateError('synthetic device failure');
  }

  @override
  Future<NotificationSettings> requestPermission({
    bool alert = true,
    bool announcement = false,
    bool badge = true,
    bool carPlay = false,
    bool criticalAlert = false,
    bool provisional = false,
    bool sound = true,
    bool providesAppNotificationSettings = false,
  }) async =>
      _Settings(permission);
}

class _Server implements HttpClientAdapter {
  final requests = <RequestOptions>[];
  final events = StreamController<RequestOptions>.broadcast(sync: true);
  Completer<void>? writeGate;
  int tokenStatus = 200;
  int refreshStatus = 201;

  List<RequestOptions> get tokenRequests =>
      requests.where((r) => r.path == '/users/me/fcm-token').toList();
  Future<RequestOptions> nextTokenRequest() => events.stream
      .firstWhere((r) => r.path == '/users/me/fcm-token')
      .timeout(const Duration(seconds: 3));

  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<Uint8List>? stream,
      Future<void>? cancelFuture) async {
    requests.add(options);
    events.add(options);
    final isToken = options.path == '/users/me/fcm-token';
    if (isToken && options.data['fcmToken'] != null) await writeGate?.future;
    return ResponseBody.fromString(
      isToken
          ? '{}'
          : '{"accessToken":"test-access","refreshToken":"test-refresh"}',
      isToken
          ? tokenStatus
          : (options.path == '/auth/refresh' ? refreshStatus : 201),
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType]
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late _Messaging messaging;
  late _Server server;
  late NotificationService service;
  late HttpClientAdapter previousAdapter;
  int connections = 0;
  bool connectFails = false;

  Future<void> signIn() => DioClient.saveTokens(
      accessToken: 'test-access', refreshToken: 'test-refresh');

  setUp(() async {
    FlutterSecureStorage.setMockInitialValues({});
    SharedPreferences.setMockInitialValues({});
    dotenv.testLoad(fileInput: '');
    DioClient.setUnauthorizedHandler(null);
    await DioClient.clearToken();
    messaging = _Messaging();
    server = _Server();
    previousAdapter = DioClient().dio.httpClientAdapter;
    DioClient().dio.httpClientAdapter = server;
    connections = 0;
    connectFails = false;
    service = NotificationService(connect: () async {
      connections++;
      if (connectFails) throw StateError('synthetic init failure');
      return messaging;
    });
  });

  tearDown(() async {
    DioClient.setUnauthorizedHandler(null);
    await service.deleteToken(clearServer: false);
    await messaging.tokens.close();
    await server.events.close();
    DioClient().dio.httpClientAdapter = previousAdapter;
    await DioClient.clearToken();
  });

  test('로그인 전 초기화는 등록하지 않고 로그인 뒤에는 등록한다', () async {
    await service.init();
    expect(connections, 0);
    expect(server.requests, isEmpty);
    await signIn();
    await service.init();
    expect(server.tokenRequests.single.data, {'fcmToken': 'device-token'});
    expect(server.tokenRequests.single.headers['Authorization'],
        'Bearer test-access');
  });

  test('저장된 알림 끄기 설정은 초기화와 재로그인에도 유지된다', () async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('notif_enabled', false);
    await signIn();
    await service.init();
    await service.init();
    expect(connections, 0);
    expect(server.requests, isEmpty);
    await prefs.setBool('notif_enabled', true);
    await service.init();
    expect(server.tokenRequests.length, 1);
  });

  test('Firebase 초기화 실패는 다음 초기화를 막지 않는다', () async {
    await signIn();
    connectFails = true;
    await service.init();
    expect(server.requests, isEmpty);
    connectFails = false;
    await service.init();
    expect(server.tokenRequests.length, 1);
  });

  test('권한 거절은 등록하지 않고 이후 허용 시 등록한다', () async {
    await signIn();
    messaging.permission = AuthorizationStatus.denied;
    await service.init();
    expect(server.requests, isEmpty);
    expect(messaging.tokens.hasListener, isFalse);
    messaging.permission = AuthorizationStatus.authorized;
    await service.init();
    expect(server.tokenRequests.length, 1);
  });

  test('반복 초기화와 해제·재등록 뒤에도 갱신 토큰은 한 번만 전송한다', () async {
    await signIn();
    await service.init();
    await service.init();
    messaging.tokens.add('refresh-one');
    await service.init();
    expect(
        server.tokenRequests
            .where((r) => r.data['fcmToken'] == 'refresh-one')
            .length,
        1);
    await service.deleteToken();
    expect(messaging.tokens.hasListener, isFalse);
    messaging.tokens.add('signed-out-token');
    await service.init();
    messaging.tokens.add('refresh-two');
    await service.init();
    expect(
        server.tokenRequests
            .where((r) => r.data['fcmToken'] == 'signed-out-token'),
        isEmpty);
    expect(
        server.tokenRequests
            .where((r) => r.data['fcmToken'] == 'refresh-two')
            .length,
        1);
  });

  test('로그아웃 중 늦게 반환된 SDK 토큰을 등록하지 않는다', () async {
    await signIn();
    messaging.tokenGate = Completer<String?>();
    final registering = service.init();
    await messaging.tokenRequested.future;
    final deleting = service.deleteToken();
    messaging.tokenGate!.complete('late-token');
    await Future.wait([registering, deleting]);
    expect(server.tokenRequests.map((r) => r.data['fcmToken']), [null]);
    expect(messaging.tokens.hasListener, isFalse);
  });

  test('이미 전송 중인 등록 뒤에 해제 요청을 보내 최종 서버 토큰을 비운다', () async {
    await signIn();
    server.writeGate = Completer<void>();
    final arrived = server.nextTokenRequest();
    final registering = service.init();
    await arrived;
    final deleting = service.deleteToken();
    server.writeGate!.complete();
    await Future.wait([registering, deleting]);
    expect(server.tokenRequests.map((r) => r.data['fcmToken']),
        ['device-token', null]);
  });

  test('등록 API 실패 후 다시 초기화하면 등록을 재시도한다', () async {
    await signIn();
    server.tokenStatus = 500;
    await service.init();
    server.tokenStatus = 200;
    await service.init();
    expect(server.tokenRequests.length, 2);
  });

  test('단말 토큰 삭제 실패와 무관하게 서버 토큰 해제를 시도한다', () async {
    await signIn();
    await service.init();
    messaging.deleteFails = true;
    await service.deleteToken();
    expect(server.tokenRequests.last.data, {'fcmToken': null});
    expect(messaging.tokens.hasListener, isFalse);
  });

  test('서버 해제 실패와 무관하게 단말 토큰 삭제를 시도한다', () async {
    await signIn();
    await service.init();
    server.tokenStatus = 500;
    await service.deleteToken();
    expect(messaging.deletions, 1);
    expect(messaging.tokens.hasListener, isFalse);
  });

  test('세션 만료 정리는 인증 없는 서버 요청 없이 단말과 구독만 정리한다', () async {
    await signIn();
    await service.init();
    await DioClient.clearToken();
    await service.deleteToken(clearServer: false);
    expect(server.tokenRequests.length, 1);
    expect(messaging.deletions, 1);
    expect(messaging.tokens.hasListener, isFalse);
  });

  test('등록 401과 재발급 실패 중 큐에 들어온 정리는 교착 없이 완료된다', () async {
    await signIn();
    server.tokenStatus = 401;
    server.refreshStatus = 401;
    Future<void>? cleanup;
    DioClient.setUnauthorizedHandler(() async {
      cleanup = service.deleteToken(clearServer: false);
    });
    await service.init().timeout(const Duration(seconds: 3));
    expect(cleanup, isNotNull);
    await cleanup!.timeout(const Duration(seconds: 3));
    expect(await DioClient.getToken(), isNull);
    expect(server.tokenRequests.length, 1);
    expect(messaging.deletions, 1);
    expect(messaging.tokens.hasListener, isFalse);
  });

  for (final method in ['register', 'login', 'oauth']) {
    test('$method 성공 후 저장한 앱 인증으로 FCM 토큰을 등록한다', () async {
      final auth = AuthService(notifications: service);
      final registered = server.nextTokenRequest();
      switch (method) {
        case 'register':
          await auth.register('test@example.test', 'password', '테스트');
        case 'login':
          await auth.login('test@example.test', 'password');
        case 'oauth':
          await auth.oauthLogin(
              provider: 'kakao',
              code: 'test-code',
              redirectUri: 'https://example.test/callback');
      }
      final request = await registered;
      expect(request.headers['Authorization'], 'Bearer test-access');
      expect(request.data, {'fcmToken': 'device-token'});
    });
  }

  test('저장된 로그인 복원 시 토큰을 동기화한다', () async {
    await signIn();
    final auth = AuthService(notifications: service);
    final registered = server.nextTokenRequest();
    expect(await auth.isLoggedIn(), isTrue);
    expect((await registered).data, {'fcmToken': 'device-token'});
  });

  test('로그아웃은 인증 제거 전에 서버 토큰을 해제한다', () async {
    await signIn();
    await service.init();
    final auth = AuthService(notifications: service);
    await auth.logout();
    final removal = server.tokenRequests.last;
    expect(removal.data, {'fcmToken': null});
    expect(removal.headers['Authorization'], 'Bearer test-access');
    expect(await DioClient.getToken(), isNull);
    expect(await DioClient.getRefreshToken(), isNull);
    expect(messaging.tokens.hasListener, isFalse);
  });
}
