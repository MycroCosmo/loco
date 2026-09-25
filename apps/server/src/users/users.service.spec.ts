import { NotFoundException } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';
import { UploadService } from '../upload/upload.service';
import { UsersService } from './users.service';

describe('UsersService account deletion', () => {
  function setup() {
    const tx = {
      user: { update: jest.fn().mockResolvedValue({ id: 'u1' }) },
      refreshToken: { updateMany: jest.fn().mockResolvedValue({ count: 2 }) },
      passwordResetToken: { updateMany: jest.fn().mockResolvedValue({ count: 1 }) },
    };
    const prisma = {
      user: { findFirst: jest.fn() },
      $transaction: jest.fn(async (work: (client: typeof tx) => Promise<void>) => work(tx)),
    };
    const service = new UsersService(
      prisma as unknown as PrismaService, {} as UploadService,
    );
    return { tx, prisma, service };
  }

  it('changes account status and revokes tokens in the same transaction callback', async () => {
    const { tx, prisma, service } = setup();
    await expect(service.deleteMe('u1')).resolves.toEqual({ message: '회원 탈퇴가 완료되었습니다.' });
    expect(prisma.$transaction).toHaveBeenCalledTimes(1);
    const deletedAt = tx.user.update.mock.calls[0][0].data.deletedAt;
    expect(deletedAt).toBeInstanceOf(Date);
    expect(tx.user.update).toHaveBeenCalledWith({
      where: { id: 'u1' }, data: { deletedAt, fcmToken: null },
    });
    expect(tx.refreshToken.updateMany).toHaveBeenCalledWith({
      where: { userId: 'u1', revokedAt: null }, data: { revokedAt: deletedAt },
    });
    expect(tx.passwordResetToken.updateMany).toHaveBeenCalledWith({
      where: { userId: 'u1', usedAt: null }, data: { usedAt: deletedAt },
    });
  });

  it('propagates a revocation failure so the real Prisma transaction can roll back', async () => {
    const { tx, service } = setup();
    tx.refreshToken.updateMany.mockRejectedValue(new Error('revocation failed'));
    await expect(service.deleteMe('u1')).rejects.toThrow('revocation failed');
    expect(tx.passwordResetToken.updateMany).not.toHaveBeenCalled();
  });

  it('filters deleted users from direct profile lookup', async () => {
    const { prisma, service } = setup();
    prisma.user.findFirst.mockResolvedValue({ id: 'u1' });
    await expect(service.findMe('u1')).resolves.toEqual({ id: 'u1' });
    expect(prisma.user.findFirst).toHaveBeenCalledWith(expect.objectContaining({
      where: { id: 'u1', deletedAt: null },
    }));
  });

  it('does not return a missing or deleted profile', async () => {
    const { prisma, service } = setup();
    prisma.user.findFirst.mockResolvedValue(null);
    await expect(service.findMe('u1')).rejects.toBeInstanceOf(NotFoundException);
  });
});
