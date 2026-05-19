import 'package:go_router/go_router.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../features/auth/providers/auth_provider.dart';
import '../../features/auth/screens/splash_screen.dart';
import '../../features/auth/screens/login_screen.dart';
import '../../features/auth/screens/oauth_callback_screen.dart';
import '../../features/auth/screens/register_screen.dart';
import '../../features/auth/screens/forgot_password_screen.dart';
import '../../features/maps/screens/map_list_screen.dart';
import '../../features/maps/screens/map_create_screen.dart';
import '../../features/maps/screens/map_detail_screen.dart';
import '../../features/places/screens/place_add_screen.dart';
import '../../features/favorites/screens/favorites_screen.dart';
import '../../features/profile/screens/my_page_screen.dart';
import '../../features/profile/screens/my_maps_screen.dart';
import '../../features/profile/screens/my_places_screen.dart';
import '../../features/profile/screens/my_reviews_screen.dart';
import '../../features/profile/screens/profile_edit_screen.dart';
import '../../shared/models/place_model.dart';

final routerProvider = Provider<GoRouter>((ref) {
  final authState = ref.watch(authStateProvider);

  return GoRouter(
    initialLocation: '/',
    redirect: (context, state) {
      if (authState.isLoading) return null;

      final loggedIn = authState.valueOrNull ?? false;
      final path = state.uri.path;
      final isPublicRoute = path == '/login' ||
          path == '/register' ||
          path == '/forgot-password' ||
          path.startsWith('/oauth/');

      if (path == '/') {
        return loggedIn ? '/maps' : '/login';
      }

      if (!loggedIn && !isPublicRoute) {
        return '/login';
      }

      if (loggedIn && (path == '/login' || path == '/register')) {
        return '/maps';
      }

      return null;
    },
    routes: [
      GoRoute(
        path: '/',
        builder: (context, state) => const SplashScreen(),
      ),
      GoRoute(
        path: '/login',
        builder: (context, state) => const LoginScreen(),
      ),
      GoRoute(
        path: '/register',
        builder: (context, state) => const RegisterScreen(),
      ),
      GoRoute(
        path: '/forgot-password',
        builder: (context, state) => const ForgotPasswordScreen(),
      ),
      GoRoute(
        path: '/oauth/:provider/callback',
        builder: (context, state) {
          return OAuthCallbackScreen(
            provider: state.pathParameters['provider']!,
            code: state.uri.queryParameters['code'],
            state: state.uri.queryParameters['state'],
            error: state.uri.queryParameters['error'],
          );
        },
      ),
      GoRoute(
        path: '/maps',
        builder: (context, state) => const MapListScreen(),
        routes: [
          GoRoute(
            path: 'create',
            builder: (context, state) => const MapCreateScreen(),
          ),
          GoRoute(
            path: ':mapId',
            builder: (context, state) {
              final mapId = state.pathParameters['mapId']!;
              return MapDetailScreen(mapId: mapId);
            },
            routes: [
              GoRoute(
                path: 'edit',
                builder: (context, state) {
                  final mapId = state.pathParameters['mapId']!;
                  return MapCreateScreen(mapId: mapId);
                },
              ),
              GoRoute(
                path: 'places/add',
                builder: (context, state) {
                  final mapId = state.pathParameters['mapId']!;
                  return PlaceAddScreen(mapId: mapId);
                },
              ),
              GoRoute(
                path: 'places/:placeId/edit',
                builder: (context, state) {
                  final mapId = state.pathParameters['mapId']!;
                  return PlaceAddScreen(
                    mapId: mapId,
                    initialPlace: state.extra as PlaceModel?,
                  );
                },
              ),
            ],
          ),
        ],
      ),
      GoRoute(
        path: '/favorites',
        builder: (context, state) => const FavoritesScreen(),
      ),
      GoRoute(
        path: '/profile',
        builder: (context, state) => const MyPageScreen(),
      ),
      GoRoute(
        path: '/profile/maps/:type',
        builder: (context, state) {
          final type = state.pathParameters['type']!;
          return MyMapsScreen(type: type);
        },
      ),
      GoRoute(
        path: '/profile/places',
        builder: (context, state) => const MyPlacesScreen(),
      ),
      GoRoute(
        path: '/profile/reviews',
        builder: (context, state) => const MyReviewsScreen(),
      ),
      GoRoute(
        path: '/profile/edit',
        builder: (context, state) => const ProfileEditScreen(),
      ),
    ],
  );
});
