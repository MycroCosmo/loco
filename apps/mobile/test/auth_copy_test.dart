import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:loco_mobile/core/network/dio_client.dart';
import 'package:loco_mobile/features/auth/screens/forgot_password_screen.dart';
import 'package:loco_mobile/features/auth/screens/login_screen.dart';

class _ForgotAdapter implements HttpClientAdapter {
  final requests = <RequestOptions>[];
  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<Uint8List>? stream,
      Future<void>? cancelFuture) async {
    requests.add(options);
    return ResponseBody.fromString('{}', 200, headers: {
      Headers.contentTypeHeader: [Headers.jsonContentType],
    });
  }
  @override
  void close({bool force = false}) {}
}

void main() {
  setUp(() {
    FlutterSecureStorage.setMockInitialValues({});
    dotenv.testLoad(fileInput: '');
  });
  testWidgets('좁은 화면·글씨 2배·키보드에서도 로그인과 간편 로그인에 접근한다', (tester) async {
    tester.view.physicalSize = const Size(320, 568);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(ProviderScope(child: MaterialApp(
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(textScaler: const TextScaler.linear(2),
            viewInsets: const EdgeInsets.only(bottom: 220)), child: child!),
      home: const LoginScreen(),
    )));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.byTooltip('비밀번호 보기'), findsOneWidget);
    await tester.ensureVisible(find.text('네이버로 계속하기'));
    await tester.pumpAndSettle();
    expect(find.text('네이버로 계속하기').hitTestable(), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
  testWidgets('재설정 요청은 중립 안내와 15분 유효시간을 보여준다', (tester) async {
    final adapter = _ForgotAdapter();
    final previous = DioClient().dio.httpClientAdapter;
    DioClient().dio.httpClientAdapter = adapter;
    addTearDown(() => DioClient().dio.httpClientAdapter = previous);
    await tester.pumpWidget(const ProviderScope(child: MaterialApp(home: ForgotPasswordScreen())));
    await tester.enterText(find.byType(TextFormField), 'unknown@example.test');
    await tester.tap(find.text('인증 코드 받기'));
    await tester.pumpAndSettle();
    expect(adapter.requests.single.path, '/auth/forgot-password');
    expect(adapter.requests.single.data, {'email': 'unknown@example.test'});
    expect(find.textContaining('코드는 15분 동안 유효해요'), findsOneWidget);
    expect(find.textContaining('발송을 요청했어요'), findsOneWidget);
    expect(find.textContaining('전송했습니다'), findsNothing);
  });
}
