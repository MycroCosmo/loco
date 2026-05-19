import { IsEmail, IsString, Length, MinLength } from 'class-validator';

export class ResetPasswordDto {
  @IsEmail({}, { message: '올바른 이메일을 입력하세요.' })
  email: string;

  @IsString()
  @Length(6, 6, { message: '코드는 6자리여야 합니다.' })
  code: string;

  @IsString()
  @MinLength(6, { message: '새 비밀번호는 6자 이상이어야 합니다.' })
  newPassword: string;
}
