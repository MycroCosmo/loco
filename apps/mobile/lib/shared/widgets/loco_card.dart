import 'package:flutter/material.dart';

import '../../core/constants/api_constants.dart';
import '../models/map_model.dart';
import 'cached_image.dart';
import 'visibility_badge.dart';

class LocoMapCard extends StatelessWidget {
  final MapModel map;
  final VoidCallback? onTap;
  final Widget? trailing;

  const LocoMapCard({
    super.key,
    required this.map,
    this.onTap,
    this.trailing,
  });

  @override
  Widget build(BuildContext context) {
    final visibleTags = map.tags.take(3).toList();
    final remainingTags = map.tags.length - visibleTags.length;
    final coverImageUrl = map.coverImageUrl;
    final content = _MapCardContent(
      map: map,
      trailing: trailing,
      visibleTags: visibleTags,
      remainingTags: remainingTags,
    );

    return LayoutBuilder(
      builder: (context, constraints) {
        final thumbnailSize = constraints.maxWidth < 420 ? 96.0 : 112.0;

        return Card(
          margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
          elevation: 2,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
          child: InkWell(
            onTap: onTap,
            borderRadius: BorderRadius.circular(12),
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  _MapCoverThumbnail(
                    imageUrl: coverImageUrl,
                    size: thumbnailSize,
                  ),
                  const SizedBox(width: 14),
                  Expanded(child: content),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

class _MapCoverThumbnail extends StatelessWidget {
  final String? imageUrl;
  final double size;

  const _MapCoverThumbnail({
    required this.imageUrl,
    required this.size,
  });

  @override
  Widget build(BuildContext context) {
    final imageUrl = this.imageUrl;

    return ClipRRect(
      borderRadius: BorderRadius.circular(10),
      child: SizedBox(
        width: size,
        height: size,
        child: imageUrl == null || imageUrl.isEmpty
            ? const _MapCoverPlaceholder()
            : CachedImage(
                url: ApiConstants.imageUrl(imageUrl),
                errorWidget: const _MapCoverPlaceholder(),
              ),
      ),
    );
  }
}

class _MapCoverPlaceholder extends StatelessWidget {
  const _MapCoverPlaceholder();

  @override
  Widget build(BuildContext context) {
    return Container(
      color: const Color(0xFFEFF6FF),
      alignment: Alignment.center,
      child: const Icon(
        Icons.map_outlined,
        color: Color(0xFF2563EB),
        size: 30,
      ),
    );
  }
}

class _MapCardContent extends StatelessWidget {
  final MapModel map;
  final Widget? trailing;
  final List<String> visibleTags;
  final int remainingTags;

  const _MapCardContent({
    required this.map,
    required this.trailing,
    required this.visibleTags,
    required this.remainingTags,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          map.title,
          style: Theme.of(context).textTheme.titleMedium?.copyWith(
                fontWeight: FontWeight.bold,
              ),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        if (map.description != null && map.description!.isNotEmpty) ...[
          const SizedBox(height: 6),
          Text(
            map.description!,
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: Colors.grey[600],
                ),
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
          ),
        ],
        const SizedBox(height: 8),
        SizedBox(
          height: 34,
          child: map.tags.isEmpty
              ? const SizedBox()
              : Wrap(
                  spacing: 6,
                  runSpacing: 4,
                  children: [
                    ...visibleTags.map((tag) {
                      return Chip(
                        label: Text(
                          '#$tag',
                          style: const TextStyle(fontSize: 11),
                          overflow: TextOverflow.ellipsis,
                        ),
                        visualDensity: VisualDensity.compact,
                        materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                        padding: EdgeInsets.zero,
                      );
                    }),
                    if (remainingTags > 0)
                      Chip(
                        label: Text(
                          '+$remainingTags',
                          style: const TextStyle(fontSize: 11),
                        ),
                        visualDensity: VisualDensity.compact,
                        materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                        padding: EdgeInsets.zero,
                      ),
                  ],
                ),
        ),
        const SizedBox(height: 10),
        Wrap(
          spacing: 10,
          runSpacing: 6,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            VisibilityBadge(isPublic: map.isPublic),
            if (trailing != null) trailing!,
            _MapCardStat(
              icon: Icons.place,
              label: '장소 ${map.placesCount}개',
            ),
            _MapCardStat(
              icon: Icons.group,
              label: '멤버 ${map.membersCount}명',
            ),
            _MapCardStat(
              icon: Icons.bookmark,
              label: '즐겨찾기 ${map.favoritesCount}',
            ),
          ],
        ),
      ],
    );
  }
}

class _MapCardStat extends StatelessWidget {
  final IconData icon;
  final String label;

  const _MapCardStat({
    required this.icon,
    required this.label,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 14, color: Colors.grey[500]),
        const SizedBox(width: 4),
        Text(
          label,
          style: TextStyle(fontSize: 12, color: Colors.grey[500]),
        ),
      ],
    );
  }
}
