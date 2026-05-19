import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:pointer_interceptor/pointer_interceptor.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../models/map_marker_model.dart';
import '../models/map_viewport_model.dart';
import 'kakao_map_web_helper_stub.dart'
    if (dart.library.html) 'kakao_map_web_helper_web.dart';

class KakaoMapWidget extends StatefulWidget {
  final double latitude;
  final double longitude;
  final int level;
  final List<MapMarkerModel> markers;
  final void Function(MapMarkerModel marker)? onMarkerTap;
  final void Function(MapViewportModel viewport)? onMapIdle;
  final bool draggable;
  final bool zoomable;
  final bool showZoomControls;

  const KakaoMapWidget({
    super.key,
    this.latitude = 37.5665,
    this.longitude = 126.9780,
    this.level = 5,
    this.markers = const [],
    this.onMarkerTap,
    this.onMapIdle,
    this.draggable = true,
    this.zoomable = true,
    this.showZoomControls = false,
  });

  @override
  State<KakaoMapWidget> createState() => _KakaoMapWidgetState();
}

class _KakaoMapWidgetState extends State<KakaoMapWidget> {
  static int _nextWebInstanceId = 0;
  static const _jsKey = 'e24b1cca7a6e29ce6e426a23427079da';

  WebViewController? _mobileController;
  late final int _webInstanceId = _nextWebInstanceId++;
  late int _level;
  late double _centerLatitude;
  late double _centerLongitude;
  int _webVersion = 0;

  String get _webViewId => 'kakao-map-$_webInstanceId-$_webVersion';

  @override
  void initState() {
    super.initState();
    _level = widget.level;
    _centerLatitude = widget.latitude;
    _centerLongitude = widget.longitude;
    if (!kIsWeb) _initMobile();
  }

  void _initMobile() {
    _mobileController = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..addJavaScriptChannel(
        'FlutterBridge',
        onMessageReceived: (msg) {
          if (widget.onMarkerTap == null) return;
          final data = jsonDecode(msg.message) as Map<String, dynamic>;
          final idx = data['index'] as int;
          if (idx < widget.markers.length) {
            final marker = widget.markers[idx];
            widget.onMarkerTap!(marker);
          }
        },
      )
      ..addJavaScriptChannel(
        'BoundsBridge',
        onMessageReceived: (msg) {
          if (widget.onMapIdle == null) return;
          final data = jsonDecode(msg.message) as Map<String, dynamic>;
          final viewport = MapViewportModel.fromJson(data);
          _centerLatitude = viewport.centerLat;
          _centerLongitude = viewport.centerLng;
          _level = viewport.level;
          widget.onMapIdle!(viewport);
        },
      )
      ..loadHtmlString(_buildHtml());
  }

  @override
  void didUpdateWidget(KakaoMapWidget oldWidget) {
    super.didUpdateWidget(oldWidget);

    final markersChanged = _markersChanged(oldWidget.markers, widget.markers);
    final focusChanged = oldWidget.latitude != widget.latitude ||
        oldWidget.longitude != widget.longitude ||
        oldWidget.level != widget.level;
    final optionsChanged = oldWidget.draggable != widget.draggable ||
        oldWidget.zoomable != widget.zoomable;
    if (!markersChanged && !focusChanged && !optionsChanged) return;

    final shouldFocus = focusChanged &&
        (widget.latitude != _centerLatitude ||
            widget.longitude != _centerLongitude ||
            widget.level != _level);

    if (oldWidget.level != widget.level) {
      _level = widget.level;
    }
    if (oldWidget.latitude != widget.latitude ||
        oldWidget.longitude != widget.longitude) {
      _centerLatitude = widget.latitude;
      _centerLongitude = widget.longitude;
    }

    if (optionsChanged) {
      if (kIsWeb) {
        setState(() => _webVersion++);
      } else {
        _mobileController?.loadHtmlString(_buildHtml());
      }
      return;
    }

    if (shouldFocus) {
      _focusMap(
        latitude: _centerLatitude,
        longitude: _centerLongitude,
        level: _level,
      );
    }
    if (markersChanged) {
      _updateMarkers();
    }
  }

  void _updateMarkers() {
    final markersJson = _markersJson();
    if (kIsWeb) {
      updateKakaoMapMarkersWeb(_webViewId, markersJson);
    } else {
      _mobileController?.runJavaScript(
        'window.updateKakaoMarkers(${jsonEncode(markersJson)});',
      );
    }
  }

  void _focusMap({
    required double latitude,
    required double longitude,
    int levelDelta = 0,
    int? level,
  }) {
    if (kIsWeb) {
      focusKakaoMapWeb(
        _webViewId,
        latitude: latitude,
        longitude: longitude,
        levelDelta: levelDelta,
        level: level,
      );
    } else {
      _mobileController?.runJavaScript(
        'window.focusKakaoMap($latitude, $longitude, $levelDelta, ${level ?? 'null'});',
      );
    }
  }

  @override
  void dispose() {
    if (kIsWeb) {
      disposeKakaoMapWeb(_webInstanceId);
    }
    super.dispose();
  }

  bool _markersChanged(
    List<MapMarkerModel> previous,
    List<MapMarkerModel> current,
  ) {
    if (previous.length != current.length) return true;
    for (var i = 0; i < previous.length; i++) {
      final a = previous[i];
      final b = current[i];
      if (a.id != b.id ||
          a.name != b.name ||
          a.type != b.type ||
          a.count != b.count ||
          a.groupKey != b.groupKey ||
          a.clusterKey != b.clusterKey ||
          a.precision != b.precision ||
          a.cellLatKey != b.cellLatKey ||
          a.cellLngKey != b.cellLngKey ||
          a.latitude != b.latitude ||
          a.longitude != b.longitude) {
        return true;
      }
    }
    return false;
  }

  void _setLevel(int nextLevel) {
    final clampedLevel = nextLevel.clamp(1, 14).toInt();
    if (clampedLevel == _level) return;

    setState(() {
      _level = clampedLevel;
    });
    _focusMap(
      latitude: _centerLatitude,
      longitude: _centerLongitude,
      level: _level,
    );
  }

  String _markersJson() {
    return jsonEncode(widget.markers
        .map((p) => {
              'name': p.name,
              'lat': p.latitude,
              'lng': p.longitude,
              'type': p.type.name,
              'count': p.count,
            })
        .toList());
  }

  String _buildMarkersJs() {
    final markersJson = _markersJson();
    return '''
      var markers = $markersJson;
      var canvas = document.getElementById('marker-canvas');
      var ctx = canvas.getContext('2d');
      var hitBoxes = [];
      var drawPending = false;
      var HIT_RADIUS = 24;

      window.updateKakaoMarkers = function(nextMarkers) {
        try {
          markers = typeof nextMarkers === 'string' ? JSON.parse(nextMarkers) : nextMarkers;
        } catch (e) {
          markers = [];
        }
        scheduleDraw();
      };

      window.focusKakaoMap = function(lat, lng, levelDelta, nextLevel) {
        var center = new kakao.maps.LatLng(lat, lng);
        map.setCenter(center);
        if (typeof nextLevel === 'number') {
          map.setLevel(Math.max(1, Math.min(14, nextLevel)));
        } else if (levelDelta) {
          map.setLevel(Math.max(1, Math.min(14, map.getLevel() + levelDelta)), { anchor: center });
        }
        scheduleDraw();
      };

      function syncCanvasSize() {
        var node = map.getNode();
        var width = node.offsetWidth;
        var height = node.offsetHeight;
        var dpr = window.devicePixelRatio || 1;
        if (canvas.width !== Math.round(width * dpr) ||
            canvas.height !== Math.round(height * dpr)) {
          canvas.width = Math.round(width * dpr);
          canvas.height = Math.round(height * dpr);
          canvas.style.width = width + 'px';
          canvas.style.height = height + 'px';
          ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        }
      }

      function markerRadius(marker) {
        if (marker.type === 'place') return 8;
        var digits = String(marker.count || 1).length;
        return Math.max(17, Math.min(30, 14 + digits * 3));
      }

      function markerColor(marker) {
        if (marker.type === 'group') return '#2563EB';
        if (marker.type === 'cluster') return '#F97316';
        return '#2563EB';
      }

      function drawMarker(marker, x, y, radius) {
        ctx.save();
        ctx.shadowColor = 'rgba(0,0,0,.22)';
        ctx.shadowBlur = 8;
        ctx.shadowOffsetY = 2;
        ctx.beginPath();
        ctx.arc(x, y, radius, 0, Math.PI * 2);
        ctx.fillStyle = markerColor(marker);
        ctx.fill();
        ctx.restore();
        ctx.beginPath();
        ctx.arc(x, y, radius, 0, Math.PI * 2);
        ctx.lineWidth = marker.type === 'place' ? 2 : 3;
        ctx.strokeStyle = '#FFFFFF';
        ctx.stroke();
        if (marker.type !== 'place') {
          ctx.fillStyle = '#FFFFFF';
          ctx.font = '800 13px system-ui, -apple-system, BlinkMacSystemFont, sans-serif';
          ctx.textAlign = 'center';
          ctx.textBaseline = 'middle';
          ctx.fillText(String(marker.count || 1), x, y);
        }
      }

      function drawCanvas() {
        syncCanvasSize();
        var width = map.getNode().offsetWidth;
        var height = map.getNode().offsetHeight;
        ctx.clearRect(0, 0, width, height);
        hitBoxes = [];
        var projection = map.getProjection();
        markers.forEach(function(marker, index) {
          var point = projection.containerPointFromCoords(
            new kakao.maps.LatLng(marker.lat, marker.lng)
          );
          var radius = markerRadius(marker);
          if (point.x < -radius || point.y < -radius ||
              point.x > width + radius || point.y > height + radius) {
            return;
          }
          drawMarker(marker, point.x, point.y, radius);
          hitBoxes.push({ index: index, x: point.x, y: point.y, r: Math.max(radius, HIT_RADIUS) });
        });
      }

      function scheduleDraw() {
        if (drawPending) return;
        drawPending = true;
        requestAnimationFrame(function() {
          drawPending = false;
          drawCanvas();
        });
      }

      document.getElementById('map').addEventListener('click', function(event) {
        var rect = canvas.getBoundingClientRect();
        var x = event.clientX - rect.left;
        var y = event.clientY - rect.top;
        for (var i = hitBoxes.length - 1; i >= 0; i--) {
          var hit = hitBoxes[i];
          var distance = Math.hypot(x - hit.x, y - hit.y);
          if (distance <= hit.r) {
            event.preventDefault();
            event.stopPropagation();
            if (typeof FlutterBridge !== 'undefined') {
              FlutterBridge.postMessage(JSON.stringify({ index: hit.index }));
            }
            return;
          }
        }
      }, true);

      kakao.maps.event.addListener(map, 'center_changed', scheduleDraw);
      kakao.maps.event.addListener(map, 'zoom_changed', scheduleDraw);
      kakao.maps.event.addListener(map, 'idle', scheduleDraw);
      window.addEventListener('resize', scheduleDraw);
      setTimeout(scheduleDraw, 0);
    ''';
  }

  String _buildHtml() {
    return '''
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body, html { width: 100%; height: 100%; overflow: hidden; }
    #map-wrap { position: relative; width: 100%; height: 100%; }
    #map { width: 100%; height: 100%; }
    #marker-canvas { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; z-index: 2; }
  </style>
</head>
<body>
  <div id="map-wrap">
    <div id="map"></div>
    <canvas id="marker-canvas"></canvas>
  </div>
  <script>
    (function() {
      var script = document.createElement('script');
      script.type = 'text/javascript';
      script.src = 'https://dapi.kakao.com/v2/maps/sdk.js?appkey=$_jsKey&libraries=services&autoload=false';
      script.onload = function() {
        kakao.maps.load(function() {
          var map = new kakao.maps.Map(document.getElementById('map'), {
            center: new kakao.maps.LatLng($_centerLatitude, $_centerLongitude),
            level: $_level
          });
          map.setDraggable(${widget.draggable});
          map.setZoomable(${widget.zoomable});
          function postViewport() {
            if (typeof BoundsBridge === 'undefined') return;
            var bounds = map.getBounds();
            var sw = bounds.getSouthWest();
            var ne = bounds.getNorthEast();
            var center = map.getCenter();
            BoundsBridge.postMessage(JSON.stringify({
              swLat: sw.getLat(),
              swLng: sw.getLng(),
              neLat: ne.getLat(),
              neLng: ne.getLng(),
              centerLat: center.getLat(),
              centerLng: center.getLng(),
              level: map.getLevel()
            }));
          }
          kakao.maps.event.addListener(map, 'idle', postViewport);
          setTimeout(postViewport, 0);
          ${_buildMarkersJs()}
        });
      };
      script.onerror = function() {
        document.getElementById('map').innerHTML =
          '<div style="display:flex;align-items:center;justify-content:center;height:100%;color:#9CA3AF;font-size:14px;">지도를 불러올 수 없습니다.<br>도메인 등록을 확인하세요.</div>';
      };
      document.head.appendChild(script);
    })();
  </script>
</body>
</html>
''';
  }

  String _buildWebUrl(String viewId) {
    final markersJson = Uri.encodeComponent(jsonEncode(
      widget.markers
          .map((p) => {
              'name': p.name,
              'lat': p.latitude,
              'lng': p.longitude,
                'type': p.type.name,
                'count': p.count,
                if (p.groupKey != null) 'groupKey': p.groupKey,
                if (p.clusterKey != null) 'clusterKey': p.clusterKey,
                if (p.precision != null) 'precision': p.precision,
                if (p.cellLatKey != null) 'cellLatKey': p.cellLatKey,
                if (p.cellLngKey != null) 'cellLngKey': p.cellLngKey,
              })
          .toList(),
    ));
    return '/kakao_map.html?lat=$_centerLatitude&lng=$_centerLongitude&level=$_level&markers=$markersJson&id=$viewId&draggable=${widget.draggable}&zoomable=${widget.zoomable}';
  }

  Widget _buildZoomButton({
    required IconData icon,
    required String tooltip,
    required VoidCallback? onPressed,
  }) {
    return SizedBox(
      width: 36,
      height: 36,
      child: IconButton(
        tooltip: tooltip,
        padding: EdgeInsets.zero,
        iconSize: 20,
        onPressed: onPressed,
        icon: Icon(icon),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    late final Widget map;

    if (kIsWeb) {
      final viewId = _webViewId;
      map = buildKakaoMapWeb(
        _buildWebUrl(viewId),
        viewId,
        widget.onMarkerTap != null
            ? (idx) {
                if (idx < widget.markers.length) {
                  final marker = widget.markers[idx];
                  widget.onMarkerTap!(marker);
                }
              }
            : null,
        widget.onMapIdle != null
            ? (viewport) {
                _centerLatitude = viewport.centerLat;
                _centerLongitude = viewport.centerLng;
                _level = viewport.level;
                widget.onMapIdle!(viewport);
              }
            : null,
      );
    } else {
      map = WebViewWidget(controller: _mobileController!);
    }

    if (!widget.showZoomControls) {
      return map;
    }

    return Stack(
      children: [
        Positioned.fill(child: map),
        Positioned(
          top: 8,
          right: 8,
          child: PointerInterceptor(
            child: Material(
              color: Colors.white,
              elevation: 2,
              borderRadius: BorderRadius.circular(8),
              clipBehavior: Clip.antiAlias,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  _buildZoomButton(
                    icon: Icons.add,
                    tooltip: '확대',
                    onPressed: _level <= 1 ? null : () => _setLevel(_level - 1),
                  ),
                  const SizedBox(
                    width: 28,
                    height: 1,
                    child: ColoredBox(color: Color(0xFFE5E7EB)),
                  ),
                  _buildZoomButton(
                    icon: Icons.remove,
                    tooltip: '축소',
                    onPressed:
                        _level >= 14 ? null : () => _setLevel(_level + 1),
                  ),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }
}
