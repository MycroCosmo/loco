import { ConflictException, UnauthorizedException } from '@nestjs/common';
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

describe('AuthService OAuth login', () => {
  it('does not link an OAuth identity to an existing account by email alone', async () => {
    const updateUser = jest.fn();
    const prisma = {
      user: {
        findFirst: jest.fn().mockResolvedValue(null),
        findUnique: jest.fn().mockResolvedValue({
          id: 'local-user',
          email: 'user@example.com',
          provider: Provider.LOCAL,
        }),
        update: updateUser,
      },
    } as unknown as PrismaService;
    const service = new AuthService(prisma, {} as JwtService, {} as MailService);
    jest.spyOn(service as any, 'getKakaoProfile').mockResolvedValue({
      provider: Provider.KAKAO,
      providerId: 'kakao-1',
      email: 'user@example.com',
      nickname: '사용자',
    });

    await expect(
      service.oauthLogin('kakao', {
        code: 'authorization-code',
        redirectUri: 'http://localhost/callback',
      }),
    ).rejects.toBeInstanceOf(ConflictException);
    expect(updateUser).not.toHaveBeenCalled();
  });
});
