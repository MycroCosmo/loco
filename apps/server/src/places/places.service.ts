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
import { MapPermissionsService } from '../maps/map-permissions.service';
import { CreatePlaceDto } from './dto/create-place.dto';
import { UpdatePlaceDto } from './dto/update-place.dto';
import { CreatePlaceReviewDto } from './dto/create-place-review.dto';

type MarkerRow = {
  id: string;
  name: string;
  latitude: number;
  longitude: number;
};

type MarkerCellRow = {
  precision: number;
  cellLatKey: string;
  cellLngKey: string;
  latitude: number;
  longitude: number;
  count: number;
  exactKeyCount: number;
  groupKey: string | null;
  placeId: string | null;
  placeName: string | null;
};

type MarkerCellAggregateRow = {
  cellLatKey: string;
  cellLngKey: string;
  latitude: number;
  longitude: number;
  minLat: number;
  maxLat: number;
  minLng: number;
  maxLng: number;
  count: number;
  exactKeyCount: number;
  groupKey: string | null;
  placeId: string | null;
  placeName: string | null;
};

type MarkerResponse = {
  type: 'place' | 'group' | 'cluster';
  id: string;
  name: string;
  latitude: number;
  longitude: number;
  count: number;
  groupKey?: string;
  clusterKey?: string;
  precision?: number;
  cellLatKey?: string;
  cellLngKey?: string;
};

const MARKER_CLUSTER_LEVEL = 6;
const MARKER_CLUSTER_THRESHOLD = 300;
const MARKER_RESPONSE_LIMIT = 700;
const MARKER_CELL_PRECISIONS = [1, 2, 3] as const;

@Injectable()
export class PlacesService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly uploadService: UploadService,
    private readonly notifications: NotificationsService,
    private readonly permissions: MapPermissionsService,
  ) {}

  async findAllByMap(
    mapId: string,
    userId: string,
    options: {
      cursor?: string;
      limit?: number;
      sort?: 'latest' | 'name' | 'reviews';
      search?: string;
      tag?: string;
    } = {},
  ) {
    await this.assertMapReadable(mapId, userId);

    const limit = this.normalizeLimit(options.limit);
    const search = options.search?.trim();
    const tag = options.tag?.trim();
    const tagMatchedIds = search
      ? await this.findPlaceIdsByPartialTag(mapId, search)
      : [];
    const where: Prisma.PlaceWhereInput = {
      mapId,
      ...(tag ? { tags: { has: tag } } : {}),
      ...(search
        ? {
            OR: [
              { name: { contains: search, mode: 'insensitive' as const } },
              { address: { contains: search, mode: 'insensitive' as const } },
              {
                roadAddress: {
                  contains: search,
                  mode: 'insensitive' as const,
                },
              },
              { id: { in: tagMatchedIds } },
            ],
          }
        : {}),
    };
    const places = await this.prisma.place.findMany({
      where,
      include: {
        createdBy: { select: { id: true, nickname: true, profileImageUrl: true } },
      },
      orderBy: this.placeOrderBy(options.sort),
      take: limit + 1,
      ...(options.cursor
        ? { cursor: { id: options.cursor }, skip: 1 }
        : {}),
    });

    const hasMore = places.length > limit;
    const items = hasMore ? places.slice(0, limit) : places;
    const itemsWithReviewSummary = await this.attachReviewSummaries(items);

    return {
      items: itemsWithReviewSummary,
      nextCursor: hasMore ? items[items.length - 1]?.id ?? null : null,
      hasMore,
    };
  }

  async findMarkersByMap(
    mapId: string,
    userId: string,
    bounds: {
      swLat: number;
      swLng: number;
      neLat: number;
      neLng: number;
      level?: number;
    },
  ) {
    await this.assertMapReadable(mapId, userId);

    if (
      !Number.isFinite(bounds.swLat) ||
      !Number.isFinite(bounds.swLng) ||
      !Number.isFinite(bounds.neLat) ||
      !Number.isFinite(bounds.neLng)
    ) {
      throw new BadRequestException('지도 영역 좌표가 올바르지 않습니다.');
    }

    const minLat = Math.min(bounds.swLat, bounds.neLat);
    const maxLat = Math.max(bounds.swLat, bounds.neLat);
    const minLng = Math.min(bounds.swLng, bounds.neLng);
    const maxLng = Math.max(bounds.swLng, bounds.neLng);

    const level = Number.isFinite(bounds.level) ? bounds.level! : 5;

    if (level >= MARKER_CLUSTER_LEVEL) {
      return this.findClusterMarkersByMap(mapId, {
        minLat,
        maxLat,
        minLng,
        maxLng,
        level,
      });
    }

    const rows = await this.prisma.place.findMany({
      where: {
        mapId,
        latitude: { gte: minLat, lte: maxLat },
        longitude: { gte: minLng, lte: maxLng },
      },
      select: {
        id: true,
        name: true,
        latitude: true,
        longitude: true,
      },
      orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
      take: MARKER_CLUSTER_THRESHOLD + 1,
    });

    if (rows.length > MARKER_CLUSTER_THRESHOLD) {
      return this.findClusterMarkersByMap(mapId, {
        minLat,
        maxLat,
        minLng,
        maxLng,
        level,
      });
    }

    return this.buildPlaceAndGroupMarkers(rows);
  }

  async findMarkerGroup(
    mapId: string,
    userId: string,
    latitude: number,
    longitude: number,
    groupKey?: string,
  ) {
    await this.assertMapReadable(mapId, userId);

    const [latKey, lngKey] = this.parseMarkerGroupCoordinate(
      latitude,
      longitude,
      groupKey,
    );

    const places = await this.prisma.place.findMany({
      where: {
        mapId,
        latitude: {
          gte: Number(latKey) - 0.0000005,
          lt: Number(latKey) + 0.0000005,
        },
        longitude: {
          gte: Number(lngKey) - 0.0000005,
          lt: Number(lngKey) + 0.0000005,
        },
      },
      include: {
        createdBy: { select: { id: true, nickname: true, profileImageUrl: true } },
      },
      orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
    });

    return this.attachReviewSummaries(places);
  }

  async findMarkerCluster(
    mapId: string,
    userId: string,
    options: {
      precision: number;
      cellLatKey: string;
      cellLngKey: string;
      limit?: number;
    },
  ) {
    await this.assertMapReadable(mapId, userId);

    const precision = this.parseMarkerCellPrecision(options.precision);
    const [cellLatKey, cellLngKey] = this.parseMarkerCellKeys(
      options.cellLatKey,
      options.cellLngKey,
    );
    const limit = this.normalizeLimit(options.limit);

    const cell = await this.prisma.placeMarkerCell.findUnique({
      where: {
        mapId_precision_cellLatKey_cellLngKey: {
          mapId,
          precision,
          cellLatKey,
          cellLngKey,
        },
      },
    });

    if (!cell) {
      return { count: 0, items: [], hasMore: false };
    }

    const rows = await this.prisma.$queryRaw<{ id: string }[]>(Prisma.sql`
      SELECT "id"
      FROM "places"
      WHERE "mapId" = ${mapId}
        AND ROUND("latitude"::numeric, ${precision}::int)::text = ${cellLatKey}
        AND ROUND("longitude"::numeric, ${precision}::int)::text = ${cellLngKey}
      ORDER BY "createdAt" DESC, "id" DESC
      LIMIT ${limit + 1}
    `);

    const hasMore = rows.length > limit;
    const ids = rows.slice(0, limit).map((row) => row.id);
    const places = ids.length
      ? await this.prisma.place.findMany({
          where: { id: { in: ids } },
          include: this.placeInclude,
        })
      : [];
    const placeById = new Map(places.map((place) => [place.id, place]));
    const orderedPlaces = ids
      .map((id) => placeById.get(id))
      .filter((place): place is NonNullable<typeof place> => Boolean(place));

    return {
      count: cell.count,
      items: await this.attachReviewSummaries(orderedPlaces),
      hasMore,
    };
  }

  async findOne(placeId: string, userId: string) {
    const place = await this.prisma.place.findUnique({
      where: { id: placeId },
      include: {
        ...this.placeInclude,
        map: true,
      },
    });

    if (!place) {
      throw new NotFoundException('장소를 찾을 수 없습니다.');
    }

    if (!(await this.permissions.canAccess(place.map, userId))) {
      throw new ForbiddenException('접근 권한이 없습니다.');
    }

    const [placeWithReviewSummary] = await this.attachReviewSummaries([place]);
    return placeWithReviewSummary;
  }

  async create(mapId: string, userId: string, dto: CreatePlaceDto) {
    const map = await this.prisma.map.findUnique({ where: { id: mapId } });
    if (!map) {
      throw new NotFoundException('지도방을 찾을 수 없습니다.');
    }
    if (!(await this.permissions.canAccess(map, userId))) {
      throw new ForbiddenException('비공개 지도방에는 장소를 추가할 수 없습니다.');
    }
    if (map.visibility === Visibility.PUBLIC && map.ownerId !== userId) {
      const membership = await this.prisma.mapMember.findUnique({
        where: { mapId_userId: { mapId, userId } },
      });

      if (!membership) {
        throw new ForbiddenException(
          '지도방에 가입해야 장소를 추가할 수 있습니다.',
        );
      }
    }

    const place = await this.prisma.place.create({
      data: {
        mapId,
        createdById: userId,
        name: dto.name,
        address: dto.address,
        roadAddress: dto.roadAddress,
        latitude: dto.latitude,
        longitude: dto.longitude,
        phone: dto.phone,
        category: dto.category,
        memo: dto.memo,
        tags: dto.tags ?? [],
        imageUrls: [],
      },
      include: this.placeInclude,
    });
    await this.refreshMarkerCellsForCoordinate(
      place.mapId,
      place.latitude,
      place.longitude,
    );

    const adder = await this.prisma.user.findUnique({
      where: { id: userId },
      select: { nickname: true },
    });
    this.notifications.sendToMap(mapId, userId, {
      title: '새 장소 추가',
      body: `${adder?.nickname ?? '누군가'}님이 "${map.title}"에 "${dto.name}"을 추가했습니다.`,
      data: { type: 'place_added', mapId, placeId: place.id },
    });

    return place;
  }

  async backfillAllMarkerCells() {
    const places = await this.prisma.place.findMany({
      select: { mapId: true, latitude: true, longitude: true },
    });
    await this.refreshUniqueCells(places);
    return { count: places.length };
  }

  async seedTestPlaces(
    mapId: string,
    userId: string,
    options: {
      count: number;
      centerLat: number;
      centerLng: number;
      radiusKm: number;
    },
  ) {
    const { count, centerLat, centerLng, radiusKm } = options;
    const clampedCount = Math.min(count, 20000);
    const delta = radiusKm / 111;

    const data = Array.from({ length: clampedCount }, (_, i) => ({
      mapId,
      createdById: userId,
      name: `테스트 장소 ${i + 1}`,
      address: `테스트 주소 ${i + 1}`,
      latitude: centerLat + (Math.random() * 2 - 1) * delta,
      longitude: centerLng + (Math.random() * 2 - 1) * delta,
      tags: [] as string[],
      imageUrls: [] as string[],
    }));

    const BATCH = 1000;
    let inserted = 0;
    for (let i = 0; i < data.length; i += BATCH) {
      await this.prisma.place.createMany({ data: data.slice(i, i + BATCH) });
      inserted += Math.min(BATCH, data.length - i);
    }

    return {
      inserted,
      markerCellsUpdated: false,
      nextStep: 'Run npm run marker-cells:rebuild from apps/server.',
    };
  }

  private async refreshUniqueCells(
    places: { mapId: string; latitude: number; longitude: number }[],
  ) {
    type CellKey = { mapId: string; precision: number; cellLatKey: string; cellLngKey: string };
    const unique = new Map<string, CellKey>();

    for (const place of places) {
      for (const precision of MARKER_CELL_PRECISIONS) {
        const cellLatKey = place.latitude.toFixed(precision);
        const cellLngKey = place.longitude.toFixed(precision);
        const key = `${place.mapId}:${precision}:${cellLatKey}:${cellLngKey}`;
        if (!unique.has(key)) {
          unique.set(key, { mapId: place.mapId, precision, cellLatKey, cellLngKey });
        }
      }
    }

    for (const cell of unique.values()) {
      await this.refreshMarkerCell(cell.mapId, cell.precision, cell.cellLatKey, cell.cellLngKey);
    }
  }

  async findMyPlaces(userId: string) {
    const places = await this.prisma.place.findMany({
      where: { createdById: userId },
      include: {
        ...this.placeInclude,
        map: { select: { id: true, title: true } },
      },
      orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
    });

    return this.attachReviewSummaries(places);
  }

  async findMyReviews(userId: string) {
    return this.prisma.placeReview.findMany({
      where: { userId },
      include: {
        place: {
          include: {
            createdBy: {
              select: { id: true, nickname: true, profileImageUrl: true },
            },
            map: { select: { id: true, title: true } },
          },
        },
      },
      orderBy: [{ updatedAt: 'desc' }, { id: 'desc' }],
    });
  }

  async update(placeId: string, userId: string, dto: UpdatePlaceDto) {
    const place = await this.prisma.place.findUnique({ where: { id: placeId } });

    if (!place) {
      throw new NotFoundException('장소를 찾을 수 없습니다.');
    }

    if (place.createdById !== userId) {
      throw new ForbiddenException('수정 권한이 없습니다.');
    }

    const updated = await this.prisma.place.update({
      where: { id: placeId },
      data: dto,
      include: this.placeInclude,
    });

    await this.refreshMarkerCellsForCoordinate(
      place.mapId,
      place.latitude,
      place.longitude,
    );
    if (
      updated.latitude !== place.latitude ||
      updated.longitude !== place.longitude
    ) {
      await this.refreshMarkerCellsForCoordinate(
        updated.mapId,
        updated.latitude,
        updated.longitude,
      );
    }

    return updated;
  }

  async remove(placeId: string, userId: string) {
    const place = await this.prisma.place.findUnique({
      where: { id: placeId },
      include: { map: true },
    });

    if (!place) {
      throw new NotFoundException('장소를 찾을 수 없습니다.');
    }

    const isOwner = place.map.ownerId === userId;
    const isCreator = place.createdById === userId;

    if (!isCreator && !isOwner) {
      throw new ForbiddenException('삭제 권한이 없습니다.');
    }

    await this.prisma.place.delete({ where: { id: placeId } });
    await this.refreshMarkerCellsForCoordinate(
      place.mapId,
      place.latitude,
      place.longitude,
    );

    await Promise.all(place.imageUrls.map((url) => this.uploadService.deleteFile(url)));

    return { message: '장소가 삭제되었습니다.' };
  }

  async addImage(placeId: string, userId: string, file: Express.Multer.File) {
    if (!file) {
      throw new BadRequestException('이미지 파일을 첨부해주세요.');
    }

    const place = await this.prisma.place.findUnique({ where: { id: placeId } });

    if (!place) throw new NotFoundException('장소를 찾을 수 없습니다.');
    if (place.createdById !== userId) throw new ForbiddenException('권한이 없습니다.');
    if (place.imageUrls.length >= 3) {
      throw new BadRequestException('최대 3장까지 업로드 가능합니다.');
    }

    const imageUrl = await this.uploadService.saveAsWebP(
      file.buffer,
      'places',
      { width: 1200, height: 1200, fit: 'inside' },
    );

    try {
      return await this.prisma.place.update({
        where: { id: placeId },
        data: { imageUrls: { push: imageUrl } },
        include: this.placeInclude,
      });
    } catch (error) {
      await this.uploadService.deleteFile(imageUrl);
      throw error;
    }
  }

  async removeImage(placeId: string, userId: string, imageUrl: string) {
    if (!imageUrl) {
      throw new BadRequestException('삭제할 이미지가 필요합니다.');
    }

    const place = await this.prisma.place.findUnique({ where: { id: placeId } });

    if (!place) throw new NotFoundException('장소를 찾을 수 없습니다.');
    if (place.createdById !== userId) {
      throw new ForbiddenException('이미지 삭제 권한이 없습니다.');
    }

    if (!place.imageUrls.includes(imageUrl)) {
      return this.prisma.place.findUnique({
        where: { id: placeId },
        include: this.placeInclude,
      });
    }

    const updated = await this.prisma.place.update({
      where: { id: placeId },
      data: {
        imageUrls: { set: place.imageUrls.filter((url) => url !== imageUrl) },
      },
      include: this.placeInclude,
    });

    await this.uploadService.deleteFile(imageUrl);

    return updated;
  }

  async getReviews(placeId: string, userId: string) {
    const place = await this.prisma.place.findUnique({
      where: { id: placeId },
      include: { map: true },
    });

    if (!place) {
      throw new NotFoundException('장소를 찾을 수 없습니다.');
    }

    if (!(await this.permissions.canAccess(place.map, userId))) {
      throw new ForbiddenException('접근 권한이 없습니다.');
    }

    const [stats, reviews] = await Promise.all([
      this.prisma.placeReview.aggregate({
        where: { placeId },
        _avg: { rating: true },
        _count: { _all: true },
      }),
      this.prisma.placeReview.findMany({
        where: { placeId },
        include: {
          user: { select: { id: true, nickname: true, profileImageUrl: true } },
        },
        orderBy: { updatedAt: 'desc' },
      }),
    ]);

    return {
      averageRating: stats._avg.rating ?? 0,
      reviewCount: stats._count._all,
      reviews,
    };
  }

  async submitReview(
    placeId: string,
    userId: string,
    dto: CreatePlaceReviewDto,
  ) {
    const content = dto.content.trim();
    if (!content) {
      throw new BadRequestException('리뷰 내용을 입력해주세요.');
    }

    const place = await this.prisma.place.findUnique({
      where: { id: placeId },
      include: { map: true },
    });

    if (!place) {
      throw new NotFoundException('장소를 찾을 수 없습니다.');
    }
    if (!(await this.permissions.canAccess(place.map, userId))) {
      throw new ForbiddenException('비공개 지도방에는 리뷰를 남길 수 없습니다.');
    }
    if (place.map.visibility === Visibility.PUBLIC && place.map.ownerId !== userId) {
      const membership = await this.prisma.mapMember.findUnique({
        where: { mapId_userId: { mapId: place.mapId, userId } },
      });

      if (!membership) {
        throw new ForbiddenException(
          '지도방에 가입해야 리뷰를 남길 수 있습니다.',
        );
      }
    }

    await this.prisma.placeReview.upsert({
      where: {
        placeId_userId: {
          placeId,
          userId,
        },
      },
      create: {
        placeId,
        userId,
        rating: dto.rating,
        content,
      },
      update: {
        rating: dto.rating,
        content,
      },
    });

    return this.getReviews(placeId, userId);
  }

  async deleteMyReview(placeId: string, userId: string) {
    const place = await this.prisma.place.findUnique({
      where: { id: placeId },
      include: { map: true },
    });

    if (!place) {
      throw new NotFoundException('장소를 찾을 수 없습니다.');
    }

    if (!(await this.permissions.canAccess(place.map, userId))) {
      throw new ForbiddenException('접근 권한이 없습니다.');
    }

    await this.prisma.placeReview.deleteMany({
      where: { placeId, userId },
    });

    return this.getReviews(placeId, userId);
  }

  private async assertMapReadable(mapId: string, userId: string) {
    const map = await this.prisma.map.findUnique({ where: { id: mapId } });

    if (!map) {
      throw new NotFoundException('지도방을 찾을 수 없습니다.');
    }

    if (!(await this.permissions.canAccess(map, userId))) {
      throw new ForbiddenException('접근 권한이 없습니다.');
    }

    return map;
  }

  private readonly placeInclude = {
    createdBy: {
      select: { id: true, nickname: true, profileImageUrl: true },
    },
  };

  private async attachReviewSummaries<T extends { id: string }>(places: T[]) {
    if (places.length === 0) return places;

    const summaries = await this.prisma.placeReview.groupBy({
      by: ['placeId'],
      where: { placeId: { in: places.map((place) => place.id) } },
      _avg: { rating: true },
      _count: { _all: true },
    });
    const summaryByPlaceId = new Map(
      summaries.map((summary) => [
        summary.placeId,
        {
          averageRating: summary._avg.rating ?? 0,
          reviewCount: summary._count._all,
        },
      ]),
    );

    return places.map((place) => ({
      ...place,
      averageRating: summaryByPlaceId.get(place.id)?.averageRating ?? 0,
      reviewCount: summaryByPlaceId.get(place.id)?.reviewCount ?? 0,
    }));
  }

  private normalizeLimit(limit?: number) {
    if (!limit || Number.isNaN(limit)) return 20;
    return Math.min(Math.max(limit, 1), 50);
  }

  private placeOrderBy(sort: 'latest' | 'name' | 'reviews' = 'latest') {
    if (sort === 'name') {
      return [{ name: 'asc' as const }, { id: 'desc' as const }];
    }

    if (sort === 'reviews') {
      return [
        { reviews: { _count: 'desc' as const } },
        { createdAt: 'desc' as const },
        { id: 'desc' as const },
      ];
    }

    return [{ createdAt: 'desc' as const }, { id: 'desc' as const }];
  }

  private async findPlaceIdsByPartialTag(mapId: string, search: string) {
    const pattern = `%${search}%`;
    const rows = await this.prisma.$queryRaw<{ id: string }[]>(Prisma.sql`
      SELECT "id"
      FROM "places"
      WHERE "mapId" = ${mapId}
        AND array_to_string("tags", ' ') ILIKE ${pattern}
    `);

    return rows.map((row) => row.id);
  }

  private buildPlaceAndGroupMarkers(rows: MarkerRow[]) {
    const byCoordinate = new Map<string, MarkerRow[]>();
    for (const row of rows) {
      const key = this.coordinateGroupKey(row.latitude, row.longitude);
      const group = byCoordinate.get(key);
      if (group) {
        group.push(row);
      } else {
        byCoordinate.set(key, [row]);
      }
    }

    return Array.from(byCoordinate.entries())
      .slice(0, MARKER_RESPONSE_LIMIT)
      .map(([key, group]) => {
        if (group.length === 1) {
          const place = group[0];
          return {
            type: 'place',
            id: place.id,
            name: place.name,
            latitude: place.latitude,
            longitude: place.longitude,
            count: 1,
          };
        }

        return {
          type: 'group',
          id: key,
          name: `${group.length}개 장소`,
          latitude: this.average(group.map((place) => place.latitude)),
          longitude: this.average(group.map((place) => place.longitude)),
          count: group.length,
          groupKey: key,
        };
      });
  }

  private async findClusterMarkersByMap(
    mapId: string,
    bounds: {
      minLat: number;
      maxLat: number;
      minLng: number;
      maxLng: number;
      level: number;
    },
  ) {
    const precision = this.clusterPrecision(bounds.level);
    const rows = await this.prisma.placeMarkerCell.findMany({
      where: {
        mapId,
        precision,
        maxLat: { gte: bounds.minLat },
        minLat: { lte: bounds.maxLat },
        maxLng: { gte: bounds.minLng },
        minLng: { lte: bounds.maxLng },
      },
      orderBy: [{ count: 'desc' }],
    });

    return this.buildClusterMarkersFromCells(rows);
  }

  private buildClusterMarkersFromCells(rows: MarkerCellRow[]) {
    const visibleRows = rows.slice(0, MARKER_RESPONSE_LIMIT - 1);
    const overflowRows = rows.slice(MARKER_RESPONSE_LIMIT - 1);
    const markers: MarkerResponse[] = visibleRows.map((row) =>
      this.markerCellToMarker(row),
    );

    if (overflowRows.length > 0) {
      const count = overflowRows.reduce((sum, row) => sum + row.count, 0);
      markers.push({
        type: 'cluster',
        id: 'overflow',
        name: `${count}개 장소`,
        latitude: this.weightedAverage(
          overflowRows.map((row) => ({ value: row.latitude, weight: row.count })),
        ),
        longitude: this.weightedAverage(
          overflowRows.map((row) => ({ value: row.longitude, weight: row.count })),
        ),
        count,
        clusterKey: 'overflow',
      });
    }

    return markers;
  }

  private markerCellToMarker(row: MarkerCellRow): MarkerResponse {
    const key = `${row.cellLatKey}:${row.cellLngKey}`;

    if (row.count === 1 && row.placeId && row.placeName) {
      return {
        type: 'place',
        id: row.placeId,
        name: row.placeName,
        latitude: row.latitude,
        longitude: row.longitude,
        count: 1,
      };
    }

    if (row.exactKeyCount === 1 && row.groupKey) {
      return {
        type: 'group',
        id: row.groupKey,
        name: `${row.count}개 장소`,
        latitude: row.latitude,
        longitude: row.longitude,
        count: row.count,
        groupKey: row.groupKey,
      };
    }

    return {
      type: 'cluster',
      id: key,
      name: `${row.count}개 장소`,
      latitude: row.latitude,
      longitude: row.longitude,
        count: row.count,
        clusterKey: key,
        precision: row.precision,
        cellLatKey: row.cellLatKey,
        cellLngKey: row.cellLngKey,
      };
  }

  private async refreshMarkerCellsForCoordinate(
    mapId: string,
    latitude: number,
    longitude: number,
  ) {
    for (const precision of MARKER_CELL_PRECISIONS) {
      await this.refreshMarkerCell(
        mapId,
        precision,
        latitude.toFixed(precision),
        longitude.toFixed(precision),
      );
    }
  }

  private async refreshMarkerCell(
    mapId: string,
    precision: number,
    cellLatKey: string,
    cellLngKey: string,
  ) {
    const [cell] = await this.prisma.$queryRaw<MarkerCellAggregateRow[]>(Prisma.sql`
      SELECT
        ${cellLatKey}::text AS "cellLatKey",
        ${cellLngKey}::text AS "cellLngKey",
        AVG("latitude")::float8 AS "latitude",
        AVG("longitude")::float8 AS "longitude",
        MIN("latitude")::float8 AS "minLat",
        MAX("latitude")::float8 AS "maxLat",
        MIN("longitude")::float8 AS "minLng",
        MAX("longitude")::float8 AS "maxLng",
        COUNT(*)::int AS "count",
        COUNT(DISTINCT CONCAT(
          ROUND("latitude"::numeric, 6)::text,
          ':',
          ROUND("longitude"::numeric, 6)::text
        ))::int AS "exactKeyCount",
        MIN(CONCAT(
          ROUND("latitude"::numeric, 6)::text,
          ':',
          ROUND("longitude"::numeric, 6)::text
        )) AS "groupKey",
        MIN("id") AS "placeId",
        MIN("name") AS "placeName"
      FROM "places"
      WHERE "mapId" = ${mapId}
        AND ROUND("latitude"::numeric, ${precision}::int)::text = ${cellLatKey}
        AND ROUND("longitude"::numeric, ${precision}::int)::text = ${cellLngKey}
    `);

    if (!cell || cell.count === 0) {
      await this.prisma.placeMarkerCell.deleteMany({
        where: { mapId, precision, cellLatKey, cellLngKey },
      });
      return;
    }

    await this.prisma.placeMarkerCell.upsert({
      where: {
        mapId_precision_cellLatKey_cellLngKey: {
          mapId,
          precision,
          cellLatKey,
          cellLngKey,
        },
      },
      create: {
        mapId,
        precision,
        cellLatKey,
        cellLngKey,
        latitude: cell.latitude,
        longitude: cell.longitude,
        minLat: cell.minLat,
        maxLat: cell.maxLat,
        minLng: cell.minLng,
        maxLng: cell.maxLng,
        count: cell.count,
        exactKeyCount: cell.exactKeyCount,
        groupKey: cell.exactKeyCount === 1 ? cell.groupKey : null,
        placeId: cell.count === 1 ? cell.placeId : null,
        placeName: cell.count === 1 ? cell.placeName : null,
      },
      update: {
        latitude: cell.latitude,
        longitude: cell.longitude,
        minLat: cell.minLat,
        maxLat: cell.maxLat,
        minLng: cell.minLng,
        maxLng: cell.maxLng,
        count: cell.count,
        exactKeyCount: cell.exactKeyCount,
        groupKey: cell.exactKeyCount === 1 ? cell.groupKey : null,
        placeId: cell.count === 1 ? cell.placeId : null,
        placeName: cell.count === 1 ? cell.placeName : null,
      },
    });
  }

  private coordinateGroupKey(latitude: number, longitude: number) {
    return `${this.coordinateKey(latitude)}:${this.coordinateKey(longitude)}`;
  }

  private parseMarkerGroupCoordinate(
    latitude: number,
    longitude: number,
    groupKey?: string,
  ) {
    const parts = groupKey
      ? groupKey.split(':')
      : [this.coordinateKey(latitude), this.coordinateKey(longitude)];

    if (parts.length !== 2) {
      throw new BadRequestException('좌표가 올바르지 않습니다.');
    }

    const [latKey, lngKey] = parts.map((part) => part.trim());
    const lat = Number(latKey);
    const lng = Number(lngKey);

    if (
      !latKey ||
      !lngKey ||
      !Number.isFinite(lat) ||
      !Number.isFinite(lng) ||
      lat < -90 ||
      lat > 90 ||
      lng < -180 ||
      lng > 180
    ) {
      throw new BadRequestException('좌표가 올바르지 않습니다.');
    }

    return [latKey, lngKey];
  }

  private parseMarkerCellPrecision(precision: number) {
    if (!MARKER_CELL_PRECISIONS.includes(precision as 1 | 2 | 3)) {
      throw new BadRequestException('precision 값이 올바르지 않습니다.');
    }
    return precision;
  }

  private parseMarkerCellKeys(cellLatKey: string, cellLngKey: string) {
    const latKey = cellLatKey?.trim();
    const lngKey = cellLngKey?.trim();
    const lat = Number(latKey);
    const lng = Number(lngKey);

    if (
      !latKey ||
      !lngKey ||
      !Number.isFinite(lat) ||
      !Number.isFinite(lng) ||
      lat < -90 ||
      lat > 90 ||
      lng < -180 ||
      lng > 180
    ) {
      throw new BadRequestException('좌표가 올바르지 않습니다.');
    }

    return [latKey, lngKey];
  }

  private coordinateKey(value: number) {
    return value.toFixed(6);
  }

  private clusterPrecision(level: number) {
    if (level >= 11) return 1;
    if (level >= 9) return 2;
    return 3;
  }

  private average(values: number[]) {
    return values.reduce((sum, value) => sum + value, 0) / values.length;
  }

  private weightedAverage(values: { value: number; weight: number }[]) {
    const totalWeight = values.reduce((sum, item) => sum + item.weight, 0);
    if (totalWeight === 0) return 0;
    return (
      values.reduce((sum, item) => sum + item.value * item.weight, 0) /
      totalWeight
    );
  }
}
