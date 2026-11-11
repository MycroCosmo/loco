import 'package:dio/dio.dart';

enum ErrorContext { general, login, passwordReset, oauth }

/// Presentation only: never display exception text, server bodies or OAuth state.
String userErrorMessage(
  Object? error, {
  String action = '요청을 완료하지 못했어요.',
  ErrorContext context = ErrorContext.general,
}) {
  if (error is DioException) {
    switch (error.type) {
      case DioExceptionType.connectionTimeout:
      case DioExceptionType.sendTimeout:
      case DioExceptionType.receiveTimeout:
      case DioExceptionType.transformTimeout:
      case DioExceptionType.connectionError:
        return '$action 연결을 확인해 주세요. 변경 중이었다면 반영 여부를 먼저 확인해 주세요.';
      case DioExceptionType.cancel:
        return '$action 요청이 취소됐어요. 필요하면 다시 시도해 주세요.';
      case DioExceptionType.badCertificate:
        return '$action 안전하게 연결할 수 없어요. 잠시 후 다시 시도해 주세요.';
      case DioExceptionType.badResponse:
      case DioExceptionType.unknown:
        break;
    }
    switch (error.response?.statusCode) {
      case 400:
      case 422:
        return '$action 입력 내용을 확인해 주세요.';
      case 401:
        return switch (context) {
          ErrorContext.login => '로그인하지 못했어요. 이메일과 비밀번호를 확인해 주세요.',
          ErrorContext.passwordReset => '코드가 올바르지 않거나 만료됐어요. 이메일과 코드를 확인해 주세요.',
          ErrorContext.oauth => '간편 로그인을 확인하지 못했어요. 로그인 화면에서 다시 시작해 주세요.',
          ErrorContext.general => '로그인이 필요해요. 다시 로그인해 주세요.',
        };
      case 403:
        return '$action 이 작업을 할 권한이 없어요. 접근 권한을 확인해 주세요.';
      case 404:
        return '$action 대상을 찾을 수 없어요. 목록을 새로 확인해 주세요.';
      case 409:
        return '$action 현재 상태를 확인한 뒤 다시 시도해 주세요.';
      case 413:
        return '파일이 너무 커요. 크기를 줄여 다시 선택해 주세요.';
      case 415:
        return '지원하지 않는 파일 형식이에요. 안내된 형식으로 다시 선택해 주세요.';
      case 429:
        return '요청이 많아요. 잠시 기다린 뒤 다시 시도해 주세요.';
    }
  }
  return '$action 잠시 후 다시 시도해 주세요. 변경 중이었다면 반영 여부를 먼저 확인해 주세요.';
}

const placePhotoPartialSuccess =
    '장소는 저장됐어요. 일부 사진을 올리지 못했어요. 저장된 사진을 확인한 뒤 다시 추가해 주세요.';
const mapCoverCreatePartialSuccess =
    '지도방은 만들어졌어요. 표지 사진은 올리지 못했어요. 지도방 수정에서 다시 추가해 주세요.';
const mapCoverUpdatePartialSuccess =
    '지도방은 수정됐어요. 표지 사진은 올리지 못했어요. 지도방 수정에서 다시 추가해 주세요.';
