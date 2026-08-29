import { BadRequestException, Injectable, NotFoundException } from '@nestjs/common';
import { PrismaService } from '../prisma/prisma.service';
import { UpdateUserDto } from './dto/update-user.dto';
import { UploadService } from '../upload/upload.service';

@Injectable()
export class UsersService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly uploadService: UploadService,
  ) {}

  async findMe(userId: string) {
    const user = await this.prisma.user.findUnique({
      where: { id: userId },
      select: {
        id: true,
        email: true,
        nickname: true,
        profileImageUrl: true,
        provider: true,
        createdAt: true,
        updatedAt: true,
      },
    });

    if (!user) {
      throw new NotFoundException('사용자를 찾을 수 없습니다.');
    }

    return user;
  }

  async updateMe(userId: string, dto: UpdateUserDto) {
    return this.prisma.user.update({
      where: { id: userId },
      data: dto,
      select: {
        id: true,
        email: true,
        nickname: true,
        profileImageUrl: true,
        updatedAt: true,
      },
    });
  }

  async updateProfileImage(userId: string, file: Express.Multer.File) {
    if (!file) {
      throw new BadRequestException('이미지 파일을 첨부해주세요.');
    }

    const user = await this.prisma.user.findUnique({ where: { id: userId } });
    if (!user) {
      throw new NotFoundException('사용자를 찾을 수 없습니다.');
    }

    const imageUrl = await this.uploadService.saveAsWebP(
      file.buffer,
      'profiles',
      { width: 512, height: 512, fit: 'cover' },
    );

    try {
      const updatedUser = await this.prisma.user.update({
        where: { id: userId },
        data: { profileImageUrl: imageUrl },
        select: {
          id: true,
          email: true,
          nickname: true,
          profileImageUrl: true,
          provider: true,
          updatedAt: true,
        },
      });

      if (user.profileImageUrl?.startsWith('/uploads/profiles/')) {
        await this.uploadService.deleteFile(user.profileImageUrl);
      }

      return updatedUser;
    } catch (error) {
      await this.uploadService.deleteFile(imageUrl);
      throw error;
    }
  }

  async deleteMe(userId: string) {
    const deletedAt = new Date();
    await this.prisma.$transaction([
      this.prisma.user.update({
        where: { id: userId },
        data: { deletedAt, fcmToken: null },
      }),
      this.prisma.refreshToken.updateMany({
        where: { userId, revokedAt: null },
        data: { revokedAt: deletedAt },
      }),
    ]);

    return { message: '회원 탈퇴가 완료되었습니다.' };
  }

  async updateFcmToken(userId: string, fcmToken: string | null) {
    await this.prisma.user.update({
      where: { id: userId },
      data: { fcmToken },
    });
    return { message: 'FCM 토큰이 업데이트되었습니다.' };
  }
}
