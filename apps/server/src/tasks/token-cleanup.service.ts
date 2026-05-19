import { Injectable } from '@nestjs/common';
import { Cron } from '@nestjs/schedule';
import { PrismaService } from '../prisma/prisma.service';

@Injectable()
export class TokenCleanupService {
  constructor(private readonly prisma: PrismaService) {}

  @Cron('0 3 * * *')
  async cleanupExpiredTokens() {
    const thirtyDaysAgo = new Date(Date.now() - 30 * 24 * 60 * 60 * 1000);

    await Promise.all([
      this.prisma.refreshToken.deleteMany({
        where: {
          OR: [
            { revokedAt: { not: null } },
            { expiresAt: { lt: thirtyDaysAgo } },
          ],
        },
      }),
      this.prisma.passwordResetToken.deleteMany({
        where: {
          OR: [
            { usedAt: { not: null } },
            { expiresAt: { lt: new Date() } },
          ],
        },
      }),
    ]);
  }
}
