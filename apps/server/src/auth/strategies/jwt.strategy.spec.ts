import { UnauthorizedException } from '@nestjs/common';
import { PrismaService } from '../../prisma/prisma.service';
import { JwtStrategy } from './jwt.strategy';

describe('JwtStrategy', () => {
  const findUnique = jest.fn();
  const prisma = { user: { findUnique } } as unknown as PrismaService;
  const payload = { sub: 'user-1', email: 'token@example.com' };
  const originalSecret = process.env.JWT_SECRET;

  beforeAll(() => {
    process.env.JWT_SECRET = 'test-secret-with-sufficient-length';
  });

  afterAll(() => {
    if (originalSecret === undefined) delete process.env.JWT_SECRET;
    else process.env.JWT_SECRET = originalSecret;
  });

  beforeEach(() => findUnique.mockReset());

  it('rejects an access token after the user is deleted', async () => {
    findUnique.mockResolvedValue({
      id: 'user-1',
      email: 'user@example.com',
      deletedAt: new Date(),
    });

    await expect(new JwtStrategy(prisma).validate(payload)).rejects.toBeInstanceOf(
      UnauthorizedException,
    );
  });

  it('uses the current active user identity', async () => {
    findUnique.mockResolvedValue({
      id: 'user-1',
      email: 'current@example.com',
      deletedAt: null,
    });

    await expect(new JwtStrategy(prisma).validate(payload)).resolves.toEqual({
      userId: 'user-1',
      email: 'current@example.com',
    });
  });
});
