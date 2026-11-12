import 'package:flutter/material.dart';
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
    final theme = Theme.of(tester.element(find.text('LOCO').first));
    expect(theme.colorScheme.primary, const Color(0xFF2563EB));
    expect(theme.colorScheme.onPrimary, Colors.white);
    expect(theme.colorScheme.surface, Colors.white);
    expect(theme.scaffoldBackgroundColor, Colors.white);
    final contrast = (theme.colorScheme.onPrimary.computeLuminance() + 0.05) /
        (theme.colorScheme.primary.computeLuminance() + 0.05);
    expect(contrast, greaterThanOrEqualTo(4.5));
  });
}
