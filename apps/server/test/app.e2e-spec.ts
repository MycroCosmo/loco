import { Test, TestingModule } from '@nestjs/testing';
import { INestApplication, ValidationPipe } from '@nestjs/common';
import * as request from 'supertest';
import { AppModule } from '../src/app.module';

describe('AppModule (e2e)', () => {
  let app: INestApplication;

  beforeAll(async () => {
    const moduleFixture: TestingModule = await Test.createTestingModule({
      imports: [AppModule],
    }).compile();

    app = moduleFixture.createNestApplication();
    app.setGlobalPrefix('api');
    app.useGlobalPipes(new ValidationPipe({ whitelist: true, transform: true }));
    await app.init();
  });

  afterAll(async () => {
    await app.close();
  });

  it('POST /api/auth/login - 잘못된 요청은 400을 반환', () => {
    return request(app.getHttpServer())
      .post('/api/auth/login')
      .send({ email: 'not-an-email' })
      .expect(400);
  });

  it('GET /api/users/me - 인증 없이 접근하면 401을 반환', () => {
    return request(app.getHttpServer())
      .get('/api/users/me')
      .expect(401);
  });

  it('GET /api/maps - 인증 없이 접근하면 401을 반환', () => {
    return request(app.getHttpServer())
      .get('/api/maps')
      .expect(401);
  });
});
