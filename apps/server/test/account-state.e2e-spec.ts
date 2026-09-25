import { Test, TestingModule } from '@nestjs/testing';
import { INestApplication, ValidationPipe } from '@nestjs/common';
import * as request from 'supertest';
import { randomUUID, createHash } from 'crypto';
import { AppModule } from '../src/app.module';
import { PrismaService } from '../src/prisma/prisma.service';

const enabled = process.env.RUN_DB_REGRESSION === '1';
if (enabled && new URL(process.env.DATABASE_URL ?? '').pathname !== '/loco_test') {
  throw new Error('DB regression must use the disposable loco_test database');
}

(enabled ? describe : describe.skip)('Account state with PostgreSQL', () => {
  let app: INestApplication;
  let prisma: PrismaService;
  const userIds: string[] = [];

  beforeAll(async () => {
    const module: TestingModule = await Test.createTestingModule({ imports: [AppModule] }).compile();
    app = module.createNestApplication();
    app.setGlobalPrefix('api');
    app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true, forbidNonWhitelisted: true }));
    await app.init();
    prisma = app.get(PrismaService);
  });

  afterAll(async () => {
    if (prisma && userIds.length) await prisma.user.deleteMany({ where: { id: { in: userIds } } });
    if (app) await app.close();
  });

  async function register() {
    const email = `regression-${randomUUID()}@example.test`;
    const response = await request(app.getHttpServer()).post('/api/auth/register')
      .send({ email, password: 'Test-password-123!', nickname: '회귀검증' }).expect(201);
    const user = await prisma.user.findUniqueOrThrow({ where: { email } });
    userIds.push(user.id);
    return { user, accessToken: response.body.accessToken as string, refreshToken: response.body.refreshToken as string };
  }

  it('revokes credentials and rejects the old access/refresh tokens after deletion', async () => {
    const account = await register();
    await prisma.passwordResetToken.create({ data: {
      userId: account.user.id, tokenHash: createHash('sha256').update(randomUUID()).digest('hex'),
      expiresAt: new Date(Date.now() + 60000),
    } });
    await request(app.getHttpServer()).get('/api/users/me')
      .set('Authorization', `Bearer ${account.accessToken}`).expect(200);
    await request(app.getHttpServer()).delete('/api/users/me')
      .set('Authorization', `Bearer ${account.accessToken}`).expect(200);
    await request(app.getHttpServer()).get('/api/users/me')
      .set('Authorization', `Bearer ${account.accessToken}`).expect(401);
    await request(app.getHttpServer()).post('/api/auth/refresh')
      .send({ refreshToken: account.refreshToken }).expect(401);
    expect((await prisma.user.findUniqueOrThrow({ where: { id: account.user.id } })).deletedAt).not.toBeNull();
    expect(await prisma.refreshToken.count({ where: { userId: account.user.id, revokedAt: null } })).toBe(0);
    expect(await prisma.passwordResetToken.count({ where: { userId: account.user.id, usedAt: null } })).toBe(0);
  });

  it('permits only one concurrent refresh request for the same token', async () => {
    const account = await register();
    const send = () => request(app.getHttpServer()).post('/api/auth/refresh').send({ refreshToken: account.refreshToken });
    const results = await Promise.all([send(), send()]);
    expect(results.map(result => result.status).sort()).toEqual([201, 401]);
    expect(await prisma.refreshToken.count({ where: { userId: account.user.id, revokedAt: null } })).toBe(1);
  });

  it('rolls back account deletion when token revocation fails in the database', async () => {
    const account = await register();
    await prisma.$executeRawUnsafe(`CREATE OR REPLACE FUNCTION loco_regression_fail_revoke()
      RETURNS trigger LANGUAGE plpgsql AS $$
      BEGIN RAISE EXCEPTION 'forced token revocation failure for regression'; RETURN NEW; END; $$`);
    try {
      await prisma.$executeRawUnsafe(`CREATE TRIGGER loco_regression_fail_revoke
        BEFORE UPDATE ON refresh_tokens FOR EACH ROW EXECUTE FUNCTION loco_regression_fail_revoke()`);
      await request(app.getHttpServer()).delete('/api/users/me')
        .set('Authorization', `Bearer ${account.accessToken}`).expect(500);
      expect((await prisma.user.findUniqueOrThrow({ where: { id: account.user.id } })).deletedAt).toBeNull();
      expect(await prisma.refreshToken.count({ where: { userId: account.user.id, revokedAt: null } })).toBe(1);
    } finally {
      await prisma.$executeRawUnsafe('DROP TRIGGER IF EXISTS loco_regression_fail_revoke ON refresh_tokens');
      await prisma.$executeRawUnsafe('DROP FUNCTION IF EXISTS loco_regression_fail_revoke()');
    }
  });
});
