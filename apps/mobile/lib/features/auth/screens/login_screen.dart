import '../../../core/utils/user_message.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../oauth/oauth_config.dart';
import '../providers/auth_provider.dart';

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _formKey = GlobalKey<FormState>();
  final _emailCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();
  bool _obscure = true;

  @override
  void dispose() {
    _emailCtrl.dispose();
    _passwordCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    await ref
        .read(authStateProvider.notifier)
        .login(_emailCtrl.text.trim(), _passwordCtrl.text);
  }

  void _startOAuthLogin(String provider) {
    try {
      OAuthConfig.open(provider);
    } catch (error) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(userErrorMessage(error,
              action: '간편 로그인을 시작하지 못했어요.', context: ErrorContext.oauth)),
          backgroundColor: Theme.of(context).colorScheme.error,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final authState = ref.watch(authStateProvider);

    ref.listen<AsyncValue<bool>>(authStateProvider, (_, state) {
      state.whenOrNull(
        data: (isLoggedIn) {
          if (isLoggedIn) context.go('/maps');
        },
        error: (e, _) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
                content: Text(userErrorMessage(e,
                    action: '로그인하지 못했어요.', context: ErrorContext.login)),
                backgroundColor: Theme.of(context).colorScheme.error),
          );
        },
      );
    });

    return Scaffold(
      backgroundColor: Theme.of(context).colorScheme.surface,
      body: SafeArea(
        child: LayoutBuilder(
            builder: (context, constraints) => SingleChildScrollView(
                  padding: const EdgeInsets.all(24),
                  child: ConstrainedBox(
                    constraints: BoxConstraints(
                        minHeight: (constraints.maxHeight - 48)
                            .clamp(0, double.infinity)),
                    child: Center(
                        child: ConstrainedBox(
                            constraints: const BoxConstraints(maxWidth: 480),
                            child: Form(
                              key: _formKey,
                              child: Column(
                                mainAxisAlignment: MainAxisAlignment.center,
                                crossAxisAlignment: CrossAxisAlignment.stretch,
                                children: [
                                  Center(
                                    child: Image.asset(
                                      'assets/images/loco_logo.png',
                                      width: 180,
                                      fit: BoxFit.contain,
                                    ),
                                  ),
                                  const SizedBox(height: 32),
                                  TextFormField(
                                    controller: _emailCtrl,
                                    keyboardType: TextInputType.emailAddress,
                                    decoration: const InputDecoration(
                                      labelText: '이메일',
                                      prefixIcon: Icon(Icons.email_outlined),
                                      border: OutlineInputBorder(),
                                    ),
                                    validator: (v) =>
                                        v == null || !v.contains('@')
                                            ? '올바른 이메일을 입력해 주세요'
                                            : null,
                                  ),
                                  const SizedBox(height: 16),
                                  TextFormField(
                                    controller: _passwordCtrl,
                                    obscureText: _obscure,
                                    decoration: InputDecoration(
                                      labelText: '비밀번호',
                                      prefixIcon:
                                          const Icon(Icons.lock_outline),
                                      border: const OutlineInputBorder(),
                                      suffixIcon: IconButton(
                                        tooltip:
                                            _obscure ? '비밀번호 보기' : '비밀번호 숨기기',
                                        icon: Icon(_obscure
                                            ? Icons.visibility_off
                                            : Icons.visibility),
                                        onPressed: () => setState(
                                            () => _obscure = !_obscure),
                                      ),
                                    ),
                                    validator: (v) => v == null || v.length < 6
                                        ? '6자 이상 입력해 주세요'
                                        : null,
                                  ),
                                  const SizedBox(height: 24),
                                  FilledButton(
                                    onPressed:
                                        authState.isLoading ? null : _submit,
                                    style: FilledButton.styleFrom(
                                      padding: const EdgeInsets.symmetric(
                                          vertical: 14),
                                    ),
                                    child: authState.isLoading
                                        ? SizedBox(
                                            height: 20,
                                            width: 20,
                                            child: CircularProgressIndicator(
                                                color: Theme.of(context).colorScheme.onPrimary,
                                                strokeWidth: 2))
                                        : const Text('로그인',
                                            style: TextStyle(fontSize: 16)),
                                  ),
                                  const SizedBox(height: 24),
                                  Wrap(
                                    alignment: WrapAlignment.center,
                                    crossAxisAlignment:
                                        WrapCrossAlignment.center,
                                    children: [
                                      Text('계정이 없으신가요?',
                                          style: Theme.of(context)
                                              .textTheme
                                              .bodyMedium),
                                      TextButton(
                                        onPressed: () =>
                                            context.go('/register'),
                                        child: const Text('회원가입'),
                                      ),
                                    ],
                                  ),
                                  Center(
                                    child: TextButton(
                                      onPressed: () =>
                                          context.push('/forgot-password'),
                                      child: const Text(
                                        '비밀번호를 잊으셨나요?',
                                      ),
                                    ),
                                  ),
                                  const SizedBox(height: 8),
                                  OutlinedButton(
                                    onPressed: authState.isLoading
                                        ? null
                                        : () => _startOAuthLogin('kakao'),
                                    style: OutlinedButton.styleFrom(
                                      padding: const EdgeInsets.symmetric(
                                          vertical: 13),
                                      backgroundColor: const Color(0xFFFEE500),
                                      foregroundColor: const Color(0xFF191919),
                                      side: BorderSide.none,
                                    ),
                                    child: const _OAuthButtonLabel(
                                      label: '카카오로 계속하기',
                                      color: Color(0xFF191919),
                                    ),
                                  ),
                                  const SizedBox(height: 10),
                                  OutlinedButton(
                                    onPressed: authState.isLoading
                                        ? null
                                        : () => _startOAuthLogin('naver'),
                                    style: OutlinedButton.styleFrom(
                                      padding: const EdgeInsets.symmetric(
                                          vertical: 13),
                                      backgroundColor: const Color(0xFF03C75A),
                                      foregroundColor: const Color(0xFF102A18),
                                      side: BorderSide.none,
                                    ),
                                    child: const _OAuthButtonLabel(
                                      label: '네이버로 계속하기',
                                      color: Color(0xFF102A18),
                                    ),
                                  ),
                                  // TODO: Google 로그인 버튼
                                ],
                              ),
                            ))),
                  ),
                )),
      ),
    );
  }
}

class _OAuthButtonLabel extends StatelessWidget {
  final String label;
  final Color color;

  const _OAuthButtonLabel({
    required this.label,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Text(label,
        textAlign: TextAlign.center,
        style: Theme.of(context).textTheme.labelLarge?.copyWith(color: color));
  }
}
