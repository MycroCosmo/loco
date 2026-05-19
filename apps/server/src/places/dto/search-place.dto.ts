import { IsNotEmpty, IsNumberString, IsOptional, IsString } from 'class-validator';

export class SearchPlaceDto {
  @IsString()
  @IsNotEmpty()
  query: string;

  @IsOptional()
  @IsNumberString()
  x?: string;

  @IsOptional()
  @IsNumberString()
  y?: string;

  @IsOptional()
  @IsNumberString()
  radius?: string;

  @IsOptional()
  @IsNumberString()
  page?: string;

  @IsOptional()
  @IsNumberString()
  size?: string;
}
