import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:loco_mobile/app.dart';

void main() {
  testWidgets('앱 기본 렌더링 테스트', (tester) async {
    await tester.pumpWidget(
      const ProviderScope(child: LocoApp()),
    );
    // SplashScreen이 렌더링되는지 확인
    expect(find.text('LOCO'), findsWidgets);
  });
}
