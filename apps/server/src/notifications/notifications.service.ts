import { Injectable, Logger } from '@nestjs/common';
import * as admin from 'firebase-admin';
import { PrismaService } from '../prisma/prisma.service';

@Injectable()
export class NotificationsService {
  private readonly logger = new Logger(NotificationsService.name);
  private app: admin.app.App | null = null;

  constructor(private readonly prisma: PrismaService) {
    const raw = process.env.FIREBASE_SERVICE_ACCOUNT;
    if (!raw) {
      this.logger.warn('FIREBASE_SERVICE_ACCOUNT not set — push notifications disabled');
      return;
    }
    try {
      const credential = admin.credential.cert(JSON.parse(raw) as admin.ServiceAccount);
      this.app = admin.apps.length
        ? admin.apps[0]!
        : admin.initializeApp({ credential });
    } catch (err) {
      this.logger.error('Failed to initialize Firebase Admin SDK', err);
    }
  }

  async sendToUser(
    userId: string,
    payload: { title: string; body: string; data?: Record<string, string> },
  ): Promise<void> {
    if (!this.app) return;

    const user = await this.prisma.user.findUnique({
      where: { id: userId },
      select: { fcmToken: true },
    });
    if (!user?.fcmToken) return;

    try {
      await admin.messaging(this.app).send({
        token: user.fcmToken,
        notification: { title: payload.title, body: payload.body },
        data: payload.data,
        apns: { payload: { aps: { sound: 'default' } } },
        android: { priority: 'high' },
      });
    } catch (err: any) {
      this.logger.warn(`Push failed for user ${userId}: ${err?.message}`);
      if (err?.code === 'messaging/registration-token-not-registered') {
        await this.prisma.user.update({
          where: { id: userId },
          data: { fcmToken: null },
        });
      }
    }
  }

  async sendToMap(
    mapId: string,
    excludeUserId: string,
    payload: { title: string; body: string; data?: Record<string, string> },
  ): Promise<void> {
    if (!this.app) return;

    const members = await this.prisma.mapMember.findMany({
      where: { mapId, userId: { not: excludeUserId } },
      select: { userId: true },
    });

    await Promise.allSettled(
      members.map((m) => this.sendToUser(m.userId, payload)),
    );
  }
}
