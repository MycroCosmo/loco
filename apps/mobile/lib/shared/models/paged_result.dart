class PagedResult<T> {
  final List<T> items;
  final String? nextCursor;
  final bool hasMore;

  const PagedResult({
    required this.items,
    required this.nextCursor,
    required this.hasMore,
  });

  factory PagedResult.fromJson(
    Map<String, dynamic> json,
    T Function(Map<String, dynamic> json) fromJson,
  ) {
    final list = json['items'] as List? ?? [];
    return PagedResult(
      items: list.map((item) => fromJson(item as Map<String, dynamic>)).toList(),
      nextCursor: json['nextCursor'] as String?,
      hasMore: json['hasMore'] as bool? ?? false,
    );
  }
}
