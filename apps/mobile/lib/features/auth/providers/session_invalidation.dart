import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../favorites/providers/favorites_provider.dart';
import '../../maps/providers/maps_provider.dart';
import '../../profile/providers/user_provider.dart';

void invalidateSessionProviders(Ref ref) {
  ref.invalidate(userProvider);
  ref.invalidate(favoritesProvider);
  ref.invalidate(mapDetailProvider);
  ref.invalidate(mapsListProvider(MapVisibilityFilter.public));
  ref.invalidate(mapsListProvider(MapVisibilityFilter.private));
  ref.invalidate(mapMembershipProvider);
}
