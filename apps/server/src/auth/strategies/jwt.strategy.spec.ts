import { UnauthorizedException } from '@nestjs/common';
import { PrismaService } from '../../prisma/prisma.service';
import { JwtStrategy } from './jwt.strategy';

jest.mock('../jwt.config', () => ({
  getJwtSecret: () => 'test-only-secret-not-for-production-1234567890',
}));

describe('JwtStrategy account status', () => {
  const findUnique = jest.fn();
  let strategy: JwtStrategy;

  beforeEach(() => {
    findUnique.mockReset();
    strategy = new JwtStrategy({ user: { findUnique } } as unknown as PrismaService);
  });

  it('rejects a missing subject without querying the database', async () => {
    await expect(strategy.validate({ sub: '', email: 'old@example.test' }))
      .rejects.toBeInstanceOf(UnauthorizedException);
    expect(findUnique).not.toHaveBeenCalled();
  });

  it('rejects a user that no longer exists', async () => {
    findUnique.mockResolvedValue(null);
    await expect(strategy.validate({ sub: 'u1', email: 'old@example.test' }))
      .rejects.toBeInstanceOf(UnauthorizedException);
  });

  it('rejects a deleted user even with a previously issued payload', async () => {
    findUnique.mockResolvedValue({ id: 'u1', email: 'a@example.test', deletedAt: new Date() });
    await expect(strategy.validate({ sub: 'u1', email: 'old@example.test' }))
      .rejects.toBeInstanceOf(UnauthorizedException);
  });

  it('uses the current database identity for an active user', async () => {
    findUnique.mockResolvedValue({ id: 'u1', email: 'current@example.test', deletedAt: null });
    await expect(strategy.validate({ sub: 'u1', email: 'old@example.test' }))
      .resolves.toEqual({ userId: 'u1', email: 'current@example.test' });
    expect(findUnique).toHaveBeenCalledWith({
      where: { id: 'u1' }, select: { id: true, email: true, deletedAt: true },
    });
  });

  it('does not authenticate when the database lookup fails', async () => {
    findUnique.mockRejectedValue(new Error('database unavailable'));
    await expect(strategy.validate({ sub: 'u1', email: 'a@example.test' }))
      .rejects.toThrow('database unavailable');
  });
});
