import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../services/user_service.dart';
import '../../../shared/models/user_model.dart';

final userProvider = FutureProvider<UserModel>((ref) async {
  return UserService().getMe();
});
