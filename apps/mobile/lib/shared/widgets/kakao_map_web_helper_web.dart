// ignore: avoid_web_libraries_in_flutter
import 'dart:async';
import 'dart:html' as html;
import 'dart:ui_web' as ui;

import 'package:flutter/widgets.dart';

import '../models/map_viewport_model.dart';

final _registered = <String>{};
final _subs = <String, StreamSubscription>{};
final _frames = <String, html.IFrameElement>{};

void disposeKakaoMapWeb(int webInstanceId) {
  final prefix = 'kakao-map-$webInstanceId-';
  final viewIds = _subs.keys
      .where((viewId) => viewId.startsWith(prefix))
      .toList(growable: false);

  for (final viewId in viewIds) {
    _subs.remove(viewId)?.cancel();
    _registered.remove(viewId);
    _frames.remove(viewId);
  }
}

void updateKakaoMapMarkersWeb(String viewId, String markersJson) {
  _frames[viewId]?.contentWindow?.postMessage({
    'type': 'kakaoMapUpdateMarkers',
    'mapId': viewId,
    'markers': markersJson,
  }, '*');
}

void focusKakaoMapWeb(
  String viewId, {
  required double latitude,
  required double longitude,
  int levelDelta = 0,
  int? level,
}) {
  _frames[viewId]?.contentWindow?.postMessage({
    'type': 'kakaoMapFocus',
    'mapId': viewId,
    'latitude': latitude,
    'longitude': longitude,
    'levelDelta': levelDelta,
    if (level != null) 'level': level,
  }, '*');
}

Widget buildKakaoMapWeb(
  String srcUrl,
  String viewId,
  void Function(int index)? onMarkerTap,
  void Function(MapViewportModel viewport)? onMapIdle,
) {
  if (!_registered.contains(viewId)) {
    _registered.add(viewId);
    _subs[viewId]?.cancel();

    if (onMarkerTap != null || onMapIdle != null) {
      _subs[viewId] = html.window.onMessage.listen((event) {
        final data = event.data;
        if (data == null) return;
        if (onMarkerTap != null &&
            data['type'] == 'kakaoMarkerClick' &&
            data['mapId'] == viewId) {
          onMarkerTap(data['index'] as int);
        }
        if (onMapIdle != null &&
            data['type'] == 'kakaoMapIdle' &&
            data['mapId'] == viewId) {
          onMapIdle(
            MapViewportModel.fromJson(
              Map<String, dynamic>.from(data['viewport'] as Map),
            ),
          );
        }
      });
    }

    ui.platformViewRegistry.registerViewFactory(viewId, (int id) {
      final frame = html.IFrameElement()
        ..src = srcUrl
        ..style.border = 'none'
        ..style.width = '100%'
        ..style.height = '100%';
      _frames[viewId] = frame;
      return frame;
    });
  }
  return HtmlElementView(viewType: viewId);
}
