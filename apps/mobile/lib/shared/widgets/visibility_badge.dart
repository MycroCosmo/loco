import 'package:flutter/material.dart';

class VisibilityBadge extends StatelessWidget {
  final bool isPublic;

  const VisibilityBadge({super.key, required this.isPublic});

  @override
  Widget build(BuildContext context) {
    final color = isPublic ? Colors.blue : Colors.orange;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withOpacity(0.1),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color, width: 1),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            isPublic ? Icons.public : Icons.lock,
            size: 12,
            color: color,
          ),
          const SizedBox(width: 4),
          Text(
            isPublic ? '공개' : '비공개',
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w600,
              color: color,
            ),
          ),
        ],
      ),
    );
  }
}
