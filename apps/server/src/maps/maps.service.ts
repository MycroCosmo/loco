import {
  BadRequestException,
  ForbiddenException,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { Prisma, Visibility } from '@prisma/client';
import { PrismaService } from '../prisma/prisma.service';
import { UploadService } from '../upload/upload.service';
import { NotificationsService } from '../notifications/notifications.service';
import { MapPermissionsService } from './map-permissions.service';
import { CreateMapDto } from './dto/create-map.dto';
import { UpdateMapDto } from './dto/update-map.dto';

function generateInviteCode(): string {
  return Math.random().toString(36).substring(2, 10).toUpperCase();
}

const INVITE_CODE_GENERATION_ATTEMPTS = 10;

@Injectable()
export class MapsService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly uploadService: UploadService,
    private readonly notifications: NotificationsService,
    private readonly permissions: MapPermissionsService,
  ) {}

  async findAll(
    userId: string,
    options: {
      cursor?: string;
      limit?: number;
      visibility?: Visibility;
      sort?: 'latest' | 'popular' | 'places';
      search?: string;
    } = {},
  ) {
    const limit = this.normalizeLimit(options.limit);
    const baseWhere: Prisma.MapWhereInput =
      options.visibility === Visibility.PUBLIC
        ? { visibility: Visibility.PUBLIC }
        : options.visibility === Visibility.PRIVATE
          ? {
              visibility: Visibility.PRIVATE,
              OR: [{ ownerId: userId }, { members: { some: { userId } } }],
            }
          : {
              OR: [{ visibility: Visibility.PUBLIC }, { ownerId: userId }],
            };
    const search = options.search?.trim();
    const tagMatchedIds = search
      ? await this.findMapIdsByPartialTag(userId, options.visibility, search)
      : [];
    const where: Prisma.MapWhereInput = search
      ? {
          AND: [
            baseWhere,
            {
              OR: [
                { title: { contains: search, mode: 'insensitive' as const } },
                { id: { in: tagMatchedIds } },
              ],
            },
          ],
        }
      : baseWhere;

    const maps = await this.prisma.map.findMany({
      where,
      include: {
        owner: { select: { id: true, nickname: true, profileImageUrl: true } },
        _count: { select: { places: true, favorites: true, members: true } },
      },
      orderBy: this.mapOrderBy(options.sort),
      take: limit + 1,
      ...(options.cursor
        ? { cursor: { id: options.cursor }, skip: 1 }
        : {}),
    });

    const hasMore = maps.length > limit;
    const items = hasMore ? maps.slice(0, limit) : maps;

    return {
      items,
      nextCursor: hasMore ? items[items.length - 1]?.id ?? null : null,
      hasMore,
    };
  }

  async findOne(mapId: string, userId: string) {
    const map = await this.prisma.map.findUnique({
      where: { id: mapId },
      include: {
        owner: { select: { id: true, nickname: true, profileImageUrl: true } },
        _count: { select: { places: true, favorites: true, members: true } },
      },
    });

    if (!map) {
      throw new NotFoundException('지도방을 찾을 수 없습니다.');
    }

    if (!(await this.permissions.canAccess(map, userId))) {
      throw new ForbiddenException('접근 권한이 없습니다.');
    }

    return map;
  }

  async findMine(userId: string, type: 'owned' | 'joined') {
    const where =
      type === 'joined'
        ? {
            members: { some: { userId } },
            ownerId: { not: userId },
          }
        : { ownerId: userId };

    return this.prisma.map.findMany({
      where,
      include: {
        owner: { select: { id: true, nickname: true, profileImageUrl: true } },
        _count: { select: { places: true, favorites: true, members: true } },
      },
      orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
    });
  }

  async create(userId: string, dto: CreateMapDto) {
    const inviteCode =
      dto.visibility === Visibility.PRIVATE
        ? await this.generateUniqueInviteCode()
        : null;

    return this.prisma.map.create({
      data: {
        ownerId: userId,
        title: dto.title,
        description: dto.description,
        tags: dto.tags ?? [],
        visibility: dto.visibility,
        inviteCode,
        members: {
          create: { userId },
        },
      },
    });
  }

  async getMembership(mapId: string, userId: string) {
    const map = await this.findReadableMap(mapId, userId);
    const isOwner = map.ownerId === userId;

    if (isOwner) {
      return { isMember: true };
    }

    const membership = await this.prisma.mapMember.findUnique({
      where: { mapId_userId: { mapId, userId } },
    });

    return { isMember: membership !== null };
  }

  async join(mapId: string, userId: string) {
    const map = await this.prisma.map.findUnique({ where: { id: mapId } });

    if (!map) {
      throw new NotFoundException('지도방을 찾을 수 없습니다.');
    }

    if (map.visibility !== Visibility.PUBLIC) {
      throw new ForbiddenException('비공개 지도방에는 가입할 수 없습니다.');
    }

    const result = await this.prisma.mapMember.upsert({
      where: { mapId_userId: { mapId, userId } },
      create: { mapId, userId },
      update: {},
    });

    const joiner = await this.prisma.user.findUnique({
      where: { id: userId },
      select: { nickname: true },
    });
    this.notifications.sendToUser(map.ownerId, {
      title: '새 멤버 가입',
      body: `${joiner?.nickname ?? '누군가'}님이 "${map.title}"에 가입했습니다.`,
      data: { type: 'member_joined', mapId },
    });

    return { isMember: !!result };
  }

  async getMembers(mapId: string, requesterId: string) {
    const map = await this.prisma.map.findUnique({ where: { id: mapId } });

    if (!map) {
      throw new NotFoundException('지도방을 찾을 수 없습니다.');
    }

    if (map.ownerId !== requesterId) {
      const membership = await this.prisma.mapMember.findUnique({
        where: { mapId_userId: { mapId, userId: requesterId } },
      });
      if (!membership) {
        throw new ForbiddenException('멤버 목록을 볼 권한이 없습니다.');
      }
    }

    const members = await this.prisma.mapMember.findMany({
      where: { mapId },
      include: {
        user: { select: { id: true, nickname: true, profileImageUrl: true } },
      },
      orderBy: { createdAt: 'asc' },
    });

    return members.map((m) => ({
      ...m.user,
      joinedAt: m.createdAt,
      isOwner: m.userId === map.ownerId,
    }));
  }

  async removeMember(mapId: string, targetUserId: string, requesterId: string) {
    const map = await this.prisma.map.findUnique({ where: { id: mapId } });

    if (!map) throw new NotFoundException('지도방을 찾을 수 없습니다.');
    if (map.ownerId !== requesterId) {
      throw new ForbiddenException('방장만 멤버를 내보낼 수 있습니다.');
    }
    if (targetUserId === requesterId) {
      throw new BadRequestException('자기 자신을 내보낼 수 없습니다.');
    }
    if (targetUserId === map.ownerId) {
      throw new BadRequestException('방장은 내보낼 수 없습니다.');
    }

    await this.prisma.mapMember.deleteMany({
      where: { mapId, userId: targetUserId },
    });

    return { message: '멤버를 내보냈습니다.' };
  }

  async joinByCode(inviteCode: string, userId: string) {
    const normalizedInviteCode = inviteCode.trim().toUpperCase();

    if (!normalizedInviteCode) {
      throw new BadRequestException('초대 코드를 입력해주세요.');
    }

    const map = await this.prisma.map.findUnique({
      where: { inviteCode: normalizedInviteCode },
      include: {
        owner: { select: { id: true, nickname: true, profileImageUrl: true } },
        _count: { select: { places: true, favorites: true, members: true } },
      },
    });

    if (!map || map.visibility !== Visibility.PRIVATE) {
      throw new NotFoundException('유효한 초대 코드를 찾을 수 없습니다.');
    }

    await this.prisma.mapMember.upsert({
      where: { mapId_userId: { mapId: map.id, userId } },
      create: { mapId: map.id, userId },
      update: {},
    });

    const joiner = await this.prisma.user.findUnique({
      where: { id: userId },
      select: { nickname: true },
    });
    this.notifications.sendToUser(map.ownerId, {
      title: '새 멤버 가입',
      body: `${joiner?.nickname ?? '누군가'}님이 "${map.title}"에 가입했습니다.`,
      data: { type: 'member_joined', mapId: map.id },
    });

    return this.prisma.map.findUnique({
      where: { id: map.id },
      include: {
        owner: { select: { id: true, nickname: true, profileImageUrl: true } },
        _count: { select: { places: true, favorites: true, members: true } },
      },
    });
  }

  async leave(mapId: string, userId: string) {
    const map = await this.prisma.map.findUnique({ where: { id: mapId } });

    if (!map) {
      throw new NotFoundException('지도방을 찾을 수 없습니다.');
    }

    if (map.ownerId === userId) {
      throw new ForbiddenException('내가 만든 지도방에서는 나갈 수 없습니다.');
    }

    await this.prisma.mapMember.deleteMany({
      where: { mapId, userId },
    });

    return { message: '지도방에서 나갔습니다.' };
  }

  async regenerateInviteCode(mapId: string, userId: string) {
    const map = await this.prisma.map.findUnique({ where: { id: mapId } });

    if (!map) {
      throw new NotFoundException('지도방을 찾을 수 없습니다.');
    }

    if (map.ownerId !== userId) {
      throw new ForbiddenException('초대 코드 재생성 권한이 없습니다.');
    }

    if (map.visibility !== Visibility.PRIVATE) {
      throw new BadRequestException('비공개 지도방만 초대 코드를 재생성할 수 있습니다.');
    }

    const inviteCode = await this.generateUniqueInviteCode(map.inviteCode);

    return this.prisma.map.update({
      where: { id: mapId },
      data: { inviteCode },
      include: {
        owner: { select: { id: true, nickname: true, profileImageUrl: true } },
        _count: { select: { places: true, favorites: true, members: true } },
      },
    });
  }

  async update(mapId: string, userId: string, dto: UpdateMapDto) {
    const map = await this.prisma.map.findUnique({ where: { id: mapId } });

    if (!map) {
      throw new NotFoundException('지도방을 찾을 수 없습니다.');
    }

    if (map.ownerId !== userId) {
      throw new ForbiddenException('수정 권한이 없습니다.');
    }

    const data = {
      ...dto,
      ...(dto.visibility === Visibility.PRIVATE && !map.inviteCode
        ? { inviteCode: await this.generateUniqueInviteCode() }
        : {}),
      ...(dto.visibility === Visibility.PUBLIC ? { inviteCode: null } : {}),
    };

    return this.prisma.map.update({
      where: { id: mapId },
      data,
    });
  }

  async remove(mapId: string, userId: string, force = false) {
    const map = await this.prisma.map.findUnique({
      where: { id: mapId },
      include: { places: true },
    });

    if (!map) {
      throw new NotFoundException('지도방을 찾을 수 없습니다.');
    }

    if (map.ownerId !== userId) {
      throw new ForbiddenException('삭제 권한이 없습니다.');
    }

    const otherPlaces = map.places.filter((p) => p.createdById !== userId);

    if (otherPlaces.length > 0 && !force) {
      throw new BadRequestException(
        `다른 사용자의 장소 ${otherPlaces.length}개가 있어 삭제할 수 없습니다. 강제 삭제를 사용하세요.`,
      );
    }

    await this.prisma.map.delete({ where: { id: mapId } });

    if (map.coverImageUrl) {
      await this.uploadService.deleteFile(map.coverImageUrl);
    }

    await Promise.all(
      map.places.flatMap((p) =>
        p.imageUrls.map((url) => this.uploadService.deleteFile(url)),
      ),
    );

    return { message: '지도방이 삭제되었습니다.' };
  }

  async updateCoverImage(
    mapId: string,
    userId: string,
    file: Express.Multer.File,
  ) {
    if (!file) {
      throw new BadRequestException('이미지 파일을 첨부해주세요.');
    }

    const map = await this.prisma.map.findUnique({ where: { id: mapId } });

    if (!map) throw new NotFoundException('지도방을 찾을 수 없습니다.');
    if (map.ownerId !== userId) throw new ForbiddenException('권한이 없습니다.');

    const imageUrl = await this.uploadService.saveAsWebP(
      file.buffer,
      'maps',
      { width: 800, height: 400, fit: 'cover' },
    );

    try {
      const updatedMap = await this.prisma.map.update({
        where: { id: mapId },
        data: { coverImageUrl: imageUrl },
      });

      if (map.coverImageUrl) {
        await this.uploadService.deleteFile(map.coverImageUrl);
      }

      return updatedMap;
    } catch (error) {
      await this.uploadService.deleteFile(imageUrl);
      throw error;
    }
  }

  private async findReadableMap(mapId: string, userId: string) {
    const map = await this.prisma.map.findUnique({ where: { id: mapId } });

    if (!map) {
      throw new NotFoundException('지도방을 찾을 수 없습니다.');
    }

    if (!(await this.permissions.canAccess(map, userId))) {
      throw new ForbiddenException('접근 권한이 없습니다.');
    }

    return map;
  }

  private normalizeLimit(limit?: number) {
    if (!limit || Number.isNaN(limit)) return 20;
    return Math.min(Math.max(limit, 1), 50);
  }

  private mapOrderBy(sort: 'latest' | 'popular' | 'places' = 'latest') {
    if (sort === 'popular') {
      return [
        { favorites: { _count: 'desc' as const } },
        { createdAt: 'desc' as const },
        { id: 'desc' as const },
      ];
    }

    if (sort === 'places') {
      return [
        { places: { _count: 'desc' as const } },
        { createdAt: 'desc' as const },
        { id: 'desc' as const },
      ];
    }

    return [{ createdAt: 'desc' as const }, { id: 'desc' as const }];
  }

  private async findMapIdsByPartialTag(
    userId: string,
    visibility: Visibility | undefined,
    search: string,
  ) {
    const pattern = `%${search}%`;
    const visibilityCondition =
      visibility === Visibility.PUBLIC
        ? Prisma.sql`"visibility"::text = ${Visibility.PUBLIC}`
        : visibility === Visibility.PRIVATE
          ? Prisma.sql`
              "visibility"::text = ${Visibility.PRIVATE}
              AND (
                "ownerId" = ${userId}
                OR EXISTS (
                  SELECT 1
                  FROM "map_members" mm
                  WHERE mm."mapId" = "maps"."id"
                    AND mm."userId" = ${userId}
                )
              )
            `
          : Prisma.sql`
              ("visibility"::text = ${Visibility.PUBLIC} OR "ownerId" = ${userId})
            `;

    const rows = await this.prisma.$queryRaw<{ id: string }[]>(Prisma.sql`
      SELECT "id"
      FROM "maps"
      WHERE ${visibilityCondition}
        AND array_to_string("tags", ' ') ILIKE ${pattern}
    `);

    return rows.map((row) => row.id);
  }

  private async generateUniqueInviteCode(excludeCode?: string | null) {
    for (let i = 0; i < INVITE_CODE_GENERATION_ATTEMPTS; i++) {
      const inviteCode = generateInviteCode();
      if (inviteCode === excludeCode) continue;

      const existing = await this.prisma.map.findUnique({
        where: { inviteCode },
        select: { id: true },
      });

      if (!existing) return inviteCode;
    }

    throw new BadRequestException('초대 코드 생성에 실패했습니다. 다시 시도해주세요.');
  }
}
