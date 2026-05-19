import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'package:flutter_web_plugins/url_strategy.dart';
import 'features/notifications/notification_service.dart';
import 'app.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await dotenv.load(fileName: '.env');
  usePathUrlStrategy();

  // FCM 초기화 (firebase_options.dart가 없으면 조용히 스킵)
  await NotificationService.instance.init();

  runApp(const ProviderScope(child: LocoApp()));
}
