import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';

export 'package:cached_network_image/cached_network_image.dart'
    show CachedNetworkImageProvider;

class CachedImage extends StatelessWidget {
  final String url;
  final BoxFit fit;
  final Widget? errorWidget;
  final Widget? placeholder;

  const CachedImage({
    super.key,
    required this.url,
    this.fit = BoxFit.cover,
    this.errorWidget,
    this.placeholder,
  });

  @override
  Widget build(BuildContext context) {
    return CachedNetworkImage(
      imageUrl: url,
      fit: fit,
      placeholder: (_, __) =>
          placeholder ?? const ColoredBox(color: Color(0xFFE5E7EB)),
      errorWidget: (_, __, ___) =>
          errorWidget ??
          const ColoredBox(
            color: Color(0xFFE5E7EB),
            child: Center(
              child: Icon(Icons.broken_image_outlined, color: Color(0xFF9CA3AF)),
            ),
          ),
    );
  }
}
