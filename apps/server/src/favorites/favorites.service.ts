import {
  Injectable,
  NotFoundException,
  ForbiddenException,
  ConflictException,
} from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import { CreateFavoriteDto } from './dto/create-favorite.dto';

@Injectable()
export class FavoritesService {
  constructor(private readonly prisma: PrismaService) {}

  async findMyFavorites(userId: string) {
    return this.prisma.favorite.findMany({
      where: { userId },
      include: {
        map: {
          include: {
            owner: { select: { id: true, nickname: true } },
            _count: { select: { places: true, favorites: true, members: true } },
          },
        },
      },
      orderBy: { createdAt: 'desc' },
    });
  }

  async addFavorite(userId: string, dto: CreateFavoriteDto) {
    const map = await this.prisma.map.findUnique({ where: { id: dto.mapId } });
    if (!map) {
      throw new NotFoundException('지도방을 찾을 수 없습니다.');
    }

    if (map.ownerId === userId) {
      throw new ForbiddenException('내가 만든 지도방은 즐겨찾기할 수 없습니다.');
    }

    try {
      return await this.prisma.favorite.create({
        data: { userId, mapId: dto.mapId },
      });
    } catch (error) {
      if (
        error instanceof Prisma.PrismaClientKnownRequestError &&
        error.code === 'P2002'
      ) {
        throw new ConflictException('이미 즐겨찾기한 지도방입니다.');
      }
      throw error;
    }
  }

  async removeFavorite(favoriteId: string, userId: string) {
    const favorite = await this.prisma.favorite.findUnique({
      where: { id: favoriteId },
    });

    if (!favorite) {
      throw new NotFoundException('즐겨찾기를 찾을 수 없습니다.');
    }

    if (favorite.userId !== userId) {
      throw new ForbiddenException('삭제 권한이 없습니다.');
    }

    await this.prisma.favorite.delete({ where: { id: favoriteId } });

    return { message: '즐겨찾기가 삭제되었습니다.' };
  }
}
