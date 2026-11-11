import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'core/router/app_router.dart';

class LocoApp extends ConsumerWidget {
  const LocoApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(routerProvider);
    final colors = ColorScheme.fromSeed(
      seedColor: const Color(0xFF2563EB), brightness: Brightness.light,
    ).copyWith(
      primary: const Color(0xFF2563EB),
      onPrimary: Colors.white,
      surface: Colors.white,
    );

    return MaterialApp.router(
      title: 'LOCO',
      debugShowCheckedModeBanner: false,
      locale: const Locale('ko', 'KR'),
      supportedLocales: const [Locale('ko', 'KR')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      theme: ThemeData(
        colorScheme: colors,
        scaffoldBackgroundColor: colors.surface,
        appBarTheme: AppBarTheme(
          elevation: 0,
          backgroundColor: colors.surface,
          foregroundColor: colors.onSurface,
          surfaceTintColor: Colors.transparent,
        ),
        cardTheme: CardThemeData(
          elevation: 2,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        ),
        useMaterial3: true,
      ),
      routerConfig: router,
    );
  }
}
