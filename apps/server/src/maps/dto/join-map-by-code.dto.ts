import { IsNotEmpty, IsString } from 'class-validator';

export class JoinMapByCodeDto {
  @IsString()
  @IsNotEmpty()
  inviteCode: string;
}
