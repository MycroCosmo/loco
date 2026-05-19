import { Injectable, Logger } from '@nestjs/common';
import * as nodemailer from 'nodemailer';

@Injectable()
export class MailService {
  private readonly logger = new Logger(MailService.name);
  private readonly transporter: nodemailer.Transporter;
  private readonly from: string;

  constructor() {
    this.from = `"LOCO" <${process.env.SMTP_USER ?? 'noreply@loco.app'}>`;
    this.transporter = nodemailer.createTransport({
      host: process.env.SMTP_HOST ?? 'smtp.gmail.com',
      port: parseInt(process.env.SMTP_PORT ?? '587', 10),
      secure: process.env.SMTP_SECURE === 'true',
      auth: {
        user: process.env.SMTP_USER,
        pass: process.env.SMTP_PASS,
      },
    });
  }

  async sendPasswordResetCode(
    to: string,
    nickname: string,
    code: string,
  ): Promise<void> {
    try {
      await this.transporter.sendMail({
        from: this.from,
        to,
        subject: '[LOCO] 비밀번호 재설정 인증 코드',
        html: `
          <div style="font-family:sans-serif;max-width:480px;margin:0 auto;padding:24px">
            <h2 style="color:#2563EB">LOCO 비밀번호 재설정</h2>
            <p>안녕하세요, <strong>${nickname}</strong>님.</p>
            <p>아래 6자리 코드를 앱에 입력해 비밀번호를 재설정하세요.<br>
               코드는 <strong>15분</strong> 동안 유효합니다.</p>
            <div style="font-size:36px;font-weight:900;letter-spacing:12px;
                        color:#2563EB;padding:16px 0">${code}</div>
            <p style="color:#6B7280;font-size:13px">
              본인이 요청하지 않았다면 이 이메일을 무시하세요.
            </p>
          </div>`,
      });
    } catch (err) {
      this.logger.error('Failed to send password reset email', err);
      throw err;
    }
  }
}
