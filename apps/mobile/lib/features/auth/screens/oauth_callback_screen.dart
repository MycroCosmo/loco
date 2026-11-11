import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../oauth/oauth_config.dart';
import '../providers/auth_provider.dart';
import '../../../core/utils/user_message.dart';

class OAuthCallbackScreen extends ConsumerStatefulWidget {
  final String provider;
  final String? code;
  final String? state;
  final String? error;

  const OAuthCallbackScreen({
    super.key,
    required this.provider,
    this.code,
    this.state,
    this.error,
  });

  @override
  ConsumerState<OAuthCallbackScreen> createState() =>
      _OAuthCallbackScreenState();
}

class _OAuthCallbackScreenState extends ConsumerState<OAuthCallbackScreen> {
  bool _started = false;
  String? _errorMessage;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_started) return;
    _started = true;
    _completeLogin();
  }

  Future<void> _completeLogin() async {
    final code = widget.code;
    if (widget.error != null || code == null || code.isEmpty) {
      _fail('간편 로그인을 마치지 못했어요. 로그인 화면에서 다시 시작해 주세요.');
      return;
    }

    if (!OAuthConfig.verifyState(widget.provider, widget.state)) {
      _fail(
        '간편 로그인 요청을 확인하지 못했어요. 로그인 화면에서 다시 시작해 주세요.',
      );
      return;
    }

    try {
      await ref.read(authStateProvider.notifier).oauthLogin(
            provider: widget.provider,
            code: code,
            redirectUri: OAuthConfig.redirectUri(widget.provider),
            oauthState: widget.state,
          );
      final authState = ref.read(authStateProvider);
      if (authState.hasError) {
        throw authState.error ?? Exception('간편 로그인에 실패했습니다.');
      }
      if (authState.valueOrNull != true) {
        throw Exception('로그인 토큰 저장 후 인증 상태가 true가 아닙니다.');
      }
      OAuthConfig.clearState(widget.provider);
      if (!mounted) return;
      context.go('/maps');
    } catch (error) {
      _fail(userErrorMessage(error, action: '간편 로그인하지 못했어요.', context: ErrorContext.oauth));
    }
  }

  void _fail(String message) {
    OAuthConfig.clearState(widget.provider);
    if (!mounted) return;
    setState(() => _errorMessage = message);
  }

  @override
  Widget build(BuildContext context) {
    final errorMessage = _errorMessage;

    return Scaffold(
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: errorMessage == null
              ? const Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    CircularProgressIndicator(),
                    SizedBox(height: 16),
                    Text('간편 로그인 처리 중...'),
                  ],
                )
              : Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(
                      Icons.error_outline,
                      size: 48,
                      color: Theme.of(context).colorScheme.error,
                    ),
                    const SizedBox(height: 12),
                    Text(
                      errorMessage,
                      textAlign: TextAlign.center,
                    ),
                    const SizedBox(height: 20),
                    FilledButton(
                      onPressed: () => context.go('/login'),
                      child: const Text('로그인으로 돌아가기'),
                    ),
                  ],
                ),
        ),
      ),
    );
  }
}
