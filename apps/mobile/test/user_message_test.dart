import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:loco_mobile/core/utils/user_message.dart';

void main() {
  DioException response(int status) => DioException(
      requestOptions: RequestOptions(path: '/private'),
      response: Response(
          requestOptions: RequestOptions(),
          statusCode: status,
          data: {'message': 'SECRET sql token@example.test'}),
      type: DioExceptionType.badResponse,
      message: 'SECRET internal trace');

  for (final status in [400, 401, 403, 404, 409, 413, 415, 422, 429, 500]) {
    test('$status 응답 본문과 예외 원문을 사용자에게 노출하지 않는다', () {
      final message = userErrorMessage(response(status));
      expect(message, isNot(contains('SECRET')));
      expect(message, isNot(contains('/private')));
      expect(message, contains('요'));
    });
  }
  test('인증·권한·통신 실패를 구분한다', () {
    expect(userErrorMessage(response(401), context: ErrorContext.login),
        contains('이메일과 비밀번호'));
    expect(userErrorMessage(response(401)), contains('다시 로그인'));
    expect(userErrorMessage(response(403)), contains('권한'));
    expect(userErrorMessage(response(401), context: ErrorContext.passwordReset),
        contains('만료'));
    expect(
        userErrorMessage(DioException(
            requestOptions: RequestOptions(),
            type: DioExceptionType.receiveTimeout)),
        contains('반영 여부'));
    expect(userErrorMessage(Exception('SECRET')), isNot(contains('SECRET')));
  });
}
