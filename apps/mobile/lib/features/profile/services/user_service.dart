import 'package:dio/dio.dart';
import 'package:image_picker/image_picker.dart';

import '../../../core/network/dio_client.dart';
import '../../../core/constants/api_constants.dart';
import '../../../core/utils/image_utils.dart';
import '../../../shared/models/user_model.dart';

class UserService {
  final _client = DioClient();

  Future<UserModel> getMe() async {
    final response = await _client.dio.get(ApiConstants.me);
    return UserModel.fromJson(response.data as Map<String, dynamic>);
  }

  Future<UserModel> updateMe({String? nickname, String? profileImageUrl}) async {
    final response = await _client.dio.put(
      ApiConstants.me,
      data: {
        if (nickname != null) 'nickname': nickname,
        if (profileImageUrl != null) 'profileImageUrl': profileImageUrl,
      },
    );
    return UserModel.fromJson(response.data as Map<String, dynamic>);
  }

  Future<UserModel> uploadProfileImage(XFile imageFile) async {
    final bytes = await imageFile.readAsBytes();
    final formData = FormData.fromMap({
      'file': MultipartFile.fromBytes(
        bytes,
        filename: imageFile.name.isNotEmpty ? imageFile.name : 'profile.jpg',
        contentType: imageContentType(imageFile),
      ),
    });

    final response = await _client.dio.post(
      '/users/me/profile-image',
      data: formData,
    );
    return UserModel.fromJson(response.data as Map<String, dynamic>);
  }

  Future<void> deleteAccount() async {
    await _client.dio.delete(ApiConstants.me);
    await DioClient.clearToken();
  }
}
