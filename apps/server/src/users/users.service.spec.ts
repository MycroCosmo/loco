import { PrismaService } from '../prisma/prisma.service';
import { UploadService } from '../upload/upload.service';
import { UsersService } from './users.service';

describe('UsersService', () => {
  it('soft deletes the user and revokes refresh tokens in one transaction', async () => {
    const updateUser = jest.fn().mockResolvedValue({});
    const revokeTokens = jest.fn().mockResolvedValue({ count: 2 });
    const transaction = jest.fn((operations: Promise<unknown>[]) => Promise.all(operations));
    const prisma = {
      user: { update: updateUser },
      refreshToken: { updateMany: revokeTokens },
      $transaction: transaction,
    } as unknown as PrismaService;

    await new UsersService(prisma, {} as UploadService).deleteMe('user-1');

    expect(transaction).toHaveBeenCalledTimes(1);
    expect(updateUser).toHaveBeenCalledWith({
      where: { id: 'user-1' },
      data: { deletedAt: expect.any(Date), fcmToken: null },
    });
    expect(revokeTokens).toHaveBeenCalledWith({
      where: { userId: 'user-1', revokedAt: null },
      data: { revokedAt: expect.any(Date) },
    });
  });
});
