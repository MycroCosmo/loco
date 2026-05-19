import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../oauth/oauth_config.dart';
import '../providers/auth_provider.dart';

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
      _fail('간편 로그인이 취소되었거나 실패했습니다. error=${widget.error ?? 'none'}');
      return;
    }

    if (!OAuthConfig.verifyState(widget.provider, widget.state)) {
      _fail(
        '간편 로그인 요청을 확인할 수 없습니다. '
        'provider=${widget.provider}, '
        'callbackState=${widget.state ?? 'none'}, '
        'savedState=${OAuthConfig.savedState(widget.provider) ?? 'none'}',
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
      _fail('간편 로그인 실패: $error');
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
                    const Icon(
                      Icons.error_outline,
                      size: 48,
                      color: Colors.red,
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
