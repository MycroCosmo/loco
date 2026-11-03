import { UnauthorizedException } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { Provider } from '@prisma/client';
import { MailService } from '../mail/mail.service';
import { PrismaService } from '../prisma/prisma.service';
import { AuthService } from './auth.service';

describe('AuthService password reset', () => {
  const user = {
    id: 'user-1',
    email: 'user@example.com',
    provider: Provider.LOCAL,
    deletedAt: null,
  };
  const token = { id: 'reset-1', expiresAt: new Date(Date.now() + 60_000) };

  function createSubject(consumedCount: number) {
    const consume = jest.fn().mockResolvedValue({ count: consumedCount });
    const updateUser = jest.fn().mockResolvedValue({});
    const revokeRefreshTokens = jest.fn().mockResolvedValue({ count: 1 });
    const tx = {
      passwordResetToken: { updateMany: consume },
      user: { update: updateUser },
      refreshToken: { updateMany: revokeRefreshTokens },
    };
    const prisma = {
      user: { findUnique: jest.fn().mockResolvedValue(user) },
      passwordResetToken: { findFirst: jest.fn().mockResolvedValue(token) },
      $transaction: jest.fn((work: (client: typeof tx) => unknown) => work(tx)),
    } as unknown as PrismaService;
    const service = new AuthService(prisma, {} as JwtService, {} as MailService);

    return { service, consume, updateUser, revokeRefreshTokens };
  }

  it('consumes the reset token before changing the password', async () => {
    const { service, consume, updateUser, revokeRefreshTokens } = createSubject(1);

    await service.resetPassword(user.email, '123456', 'new-password');

    expect(consume).toHaveBeenCalledWith({
      where: {
        id: token.id,
        usedAt: null,
        expiresAt: { gt: expect.any(Date) },
      },
      data: { usedAt: expect.any(Date) },
    });
    expect(updateUser).toHaveBeenCalledTimes(1);
    expect(revokeRefreshTokens).toHaveBeenCalledTimes(1);
  });

  it('rejects a concurrent request that lost the token-consumption race', async () => {
    const { service, updateUser, revokeRefreshTokens } = createSubject(0);

    await expect(
      service.resetPassword(user.email, '123456', 'new-password'),
    ).rejects.toBeInstanceOf(UnauthorizedException);
    expect(updateUser).not.toHaveBeenCalled();
    expect(revokeRefreshTokens).not.toHaveBeenCalled();
  });
});
