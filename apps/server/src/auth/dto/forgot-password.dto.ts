import { IsEmail } from 'class-validator';

export class ForgotPasswordDto {
  @IsEmail({}, { message: '올바른 이메일을 입력하세요.' })
  email: string;
}
