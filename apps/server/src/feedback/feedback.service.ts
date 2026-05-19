import { Injectable } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';
import { CreateFeedbackDto } from './dto/create-feedback.dto';

@Injectable()
export class FeedbackService {
  constructor(private readonly prisma: PrismaService) {}

  async submit(userId: string, dto: CreateFeedbackDto) {
    const feedback = await this.prisma.feedback.create({
      data: {
        userId,
        content: dto.content,
      },
    });

    // TODO: AI 챗봇 연동 - 피드백 내용을 AI로 분석하거나 자동 응답 생성 예정

    return { message: '피드백이 제출되었습니다.', id: feedback.id };
  }
}
