import '../../../core/utils/user_message.dart';
import 'package:flutter/material.dart';
import '../../../core/network/dio_client.dart';
import '../../../core/constants/api_constants.dart';

class FeedbackModal extends StatefulWidget {
  const FeedbackModal({super.key});

  static Future<void> show(BuildContext context) {
    return showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (_) => const FeedbackModal(),
    );
  }

  @override
  State<FeedbackModal> createState() => _FeedbackModalState();
}

class _FeedbackModalState extends State<FeedbackModal> {
  final _ctrl = TextEditingController();
  bool _loading = false;

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final content = _ctrl.text.trim();
    if (content.length < 10) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('10자 이상 입력해 주세요.')),
      );
      return;
    }

    setState(() => _loading = true);
    try {
      await DioClient().dio.post(ApiConstants.feedback, data: {'content': content});
      if (mounted) {
        Navigator.pop(context);
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('의견을 보냈어요. 고마워요!')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
              content: Text(userErrorMessage(e, action: '의견을 보내지 못했어요.')), backgroundColor: Theme.of(context).colorScheme.error),
        );
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }

    // TODO: AI 챗봇 연동 - 피드백 내용 기반 자동 응답 기능 추가 예정
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.fromLTRB(
          24, 20, 24, MediaQuery.of(context).viewInsets.bottom + 24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              const Text('피드백 보내기',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
              IconButton(
                      tooltip: '닫기',
                icon: const Icon(Icons.close),
                onPressed: () => Navigator.pop(context),
              ),
            ],
          ),
          const SizedBox(height: 4),
          Text('서비스 개선을 위한 의견을 자유롭게 남겨주세요.',
              style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant, fontSize: 13)),
          const SizedBox(height: 16),
          TextField(
            controller: _ctrl,
            maxLines: 5,
            maxLength: 1000,
            decoration: const InputDecoration(
              hintText: '최소 10자 이상 입력해 주세요.',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          FilledButton(
            onPressed: _loading ? null : _submit,
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(context).colorScheme.primary,
              padding: const EdgeInsets.symmetric(vertical: 14),
            ),
            child: _loading
                ? SizedBox(
                    height: 20,
                    width: 20,
                    child: CircularProgressIndicator(
                        color: Theme.of(context).colorScheme.onPrimary, strokeWidth: 2))
                : const Text('제출하기', style: TextStyle(fontSize: 16)),
          ),
        ],
      ),
    );
  }
}
