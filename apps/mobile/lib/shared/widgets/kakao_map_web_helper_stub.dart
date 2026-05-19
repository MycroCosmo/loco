// 모바일(Android/iOS)에서 사용되는 stub — 실제 구현은 kakao_map_web_helper_web.dart
import 'package:flutter/widgets.dart';
import '../models/map_viewport_model.dart';

void disposeKakaoMapWeb(int webInstanceId) {}

void updateKakaoMapMarkersWeb(String viewId, String markersJson) {}

void focusKakaoMapWeb(
  String viewId, {
  required double latitude,
  required double longitude,
  int levelDelta = 0,
  int? level,
}) {}

Widget buildKakaoMapWeb(
  String srcUrl,
  String viewId,
  void Function(int index)? onMarkerTap,
  void Function(MapViewportModel viewport)? onMapIdle,
) {
  throw UnsupportedError('웹 전용 기능입니다.');
}
