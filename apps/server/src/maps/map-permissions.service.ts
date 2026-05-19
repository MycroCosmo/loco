import { Injectable } from '@nestjs/common';
import { Visibility } from '@prisma/client';
import { PrismaService } from '../prisma/prisma.service';

@Injectable()
export class MapPermissionsService {
  constructor(private readonly prisma: PrismaService) {}

  async canAccess(
    map: { id: string; ownerId: string; visibility: Visibility },
    userId: string,
  ): Promise<boolean> {
    if (map.visibility === Visibility.PUBLIC || map.ownerId === userId) {
      return true;
    }

    const membership = await this.prisma.mapMember.findUnique({
      where: { mapId_userId: { mapId: map.id, userId } },
    });

    return membership !== null;
  }
}
