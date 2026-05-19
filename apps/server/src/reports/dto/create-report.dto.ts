import { IsEnum, IsOptional, IsString, MaxLength, MinLength } from 'class-validator';
import { ReportTargetType } from '@prisma/client';

export class CreateReportDto {
  @IsEnum(ReportTargetType)
  targetType: ReportTargetType;

  @IsString()
  targetId: string;

  @IsString()
  @MinLength(5)
  @MaxLength(200)
  reason: string;

  @IsOptional()
  @IsString()
  @MaxLength(1000)
  detail?: string;
}
