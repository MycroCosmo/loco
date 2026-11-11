import 'dart:convert';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker_platform_interface/image_picker_platform_interface.dart';
import 'package:loco_mobile/core/network/dio_client.dart';
import 'package:loco_mobile/features/maps/screens/map_create_screen.dart';

class _Picker extends ImagePickerPlatform {
  @override
  Future<XFile?> getImageFromSource({required ImageSource source, ImagePickerOptions options = const ImagePickerOptions()}) async {
    final data = await rootBundle.load('assets/images/loco_logo.png');
    return XFile.fromData(data.buffer.asUint8List(), name: 'fixture.png', mimeType: 'image/png');
  }
}
class _Adapter implements HttpClientAdapter {
  final requests = <RequestOptions>[];
  @override
  Future<ResponseBody> fetch(RequestOptions options, Stream<Uint8List>? stream, Future<void>? cancelFuture) async {
    requests.add(options);
    final saved = options.path == '/maps';
    return ResponseBody.fromString(jsonEncode(saved
      ? {'id':'map-fixture', 'ownerId':'user-fixture', 'title':'샘플 지도방', 'visibility':'PUBLIC', 'createdAt':'2026-11-11T00:00:00Z'}
      : {'message':'SECRET image processor trace'}), saved ? 201 : 500,
      headers: {Headers.contentTypeHeader: [Headers.jsonContentType]});
  }
  @override
  void close({bool force = false}) {}
}

void main() {
  testWidgets('지도방 생성 후 표지 사진 실패는 재생성을 권하지 않는 부분 성공으로 안내한다', (tester) async {
    FlutterSecureStorage.setMockInitialValues({});
    dotenv.testLoad(fileInput: '');
    final adapter = _Adapter(), previousAdapter = DioClient().dio.httpClientAdapter;
    final previousPicker = ImagePickerPlatform.instance;
    DioClient().dio.httpClientAdapter = adapter;
    ImagePickerPlatform.instance = _Picker();
    addTearDown(() { DioClient().dio.httpClientAdapter = previousAdapter; ImagePickerPlatform.instance = previousPicker; });
    final router = GoRouter(routes: [
      GoRoute(path:'/', builder:(context,state)=>Scaffold(body:TextButton(onPressed:()=>context.push('/create'),child:const Text('작성')))),
      GoRoute(path:'/create',builder:(context,state)=>const MapCreateScreen()),
    ]);
    addTearDown(router.dispose);
    await tester.pumpWidget(ProviderScope(child: MaterialApp.router(routerConfig:router)));
    await tester.tap(find.text('작성')); await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('표지 사진 선택')); await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextFormField, '지도방 이름 *'), '샘플 지도방');
    await tester.ensureVisible(find.widgetWithText(FilledButton,'지도방 만들기'));
    await tester.tap(find.widgetWithText(FilledButton,'지도방 만들기')); await tester.pumpAndSettle();
    expect(adapter.requests.map((r)=>r.path), ['/maps','/maps/map-fixture/cover']);
    expect(adapter.requests.first.data['title'], '샘플 지도방');
    expect(find.textContaining('지도방은 만들어졌어요.'), findsOneWidget);
    expect(find.textContaining('지도방 수정에서 다시 추가'), findsOneWidget);
    expect(find.textContaining('SECRET'), findsNothing);
    expect(find.textContaining('만들지 못했어요'), findsNothing);
  });
}
