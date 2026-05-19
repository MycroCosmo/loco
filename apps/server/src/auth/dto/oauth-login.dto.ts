import { IsOptional, IsString, IsUrl } from 'class-validator';

export class OAuthLoginDto {
  @IsString()
  code: string;

  @IsString()
  @IsUrl({ require_tld: false })
  redirectUri: string;

  @IsOptional()
  @IsString()
  state?: string;
}
