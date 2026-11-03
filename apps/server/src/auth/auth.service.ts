import {
  Injectable,
  UnauthorizedException,
  ConflictException,
  BadRequestException,
} from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { Provider } from '@prisma/client';
import * as bcrypt from 'bcrypt';
import { createHash, randomBytes } from 'crypto';
import { PrismaService } from '../prisma/prisma.service';
import { MailService } from '../mail/mail.service';
import { LoginDto } from './dto/login.dto';
import { OAuthLoginDto } from './dto/oauth-login.dto';
import { RegisterDto } from './dto/register.dto';

type OAuthProvider = 'kakao' | 'naver';
const REFRESH_TOKEN_TTL_DAYS = 30;
const PASSWORD_RESET_TTL_MINUTES = 15;

interface OAuthProfile {
  provider: Provider;
  providerId: string;
  email: string;
  nickname: string;
  profileImageUrl?: string;
}

@Injectable()
export class AuthService {
  constructor(
    private readonly prisma: PrismaService,
    private readonly jwtService: JwtService,
    private readonly mailService: MailService,
  ) {}

  async register(dto: RegisterDto) {
    const existing = await this.prisma.user.findUnique({
      where: { email: dto.email },
    });

    if (existing) {
      throw new ConflictException('이미 사용 중인 이메일입니다.');
    }

    const hashed = await bcrypt.hash(dto.password, 10);

    const user = await this.prisma.user.create({
      data: {
        email: dto.email,
        password: hashed,
        nickname: dto.nickname,
      },
    });

    return this.issueTokenPair(user);
  }

  async login(dto: LoginDto) {
    const user = await this.prisma.user.findUnique({
      where: { email: dto.email },
    });

    if (!user || !user.password) {
      throw new UnauthorizedException('이메일 또는 비밀번호가 올바르지 않습니다.');
    }

    if (user.deletedAt) {
      throw new UnauthorizedException('탈퇴한 계정입니다.');
    }

    const isPasswordValid = await bcrypt.compare(dto.password, user.password);
    if (!isPasswordValid) {
      throw new UnauthorizedException('이메일 또는 비밀번호가 올바르지 않습니다.');
    }

    return this.issueTokenPair(user);

    // TODO: Naver OAuth 연동
    // TODO: Kakao OAuth 연동
    // TODO: Google OAuth 연동
  }

  async oauthLogin(provider: OAuthProvider, dto: OAuthLoginDto) {
    const profile =
      provider === 'kakao'
        ? await this.getKakaoProfile(dto)
        : provider === 'naver'
          ? await this.getNaverProfile(dto)
          : null;

    if (!profile) {
      throw new BadRequestException('지원하지 않는 간편 로그인입니다.');
    }

    const existingByProvider = await this.prisma.user.findFirst({
      where: {
        provider: profile.provider,
        providerId: profile.providerId,
      },
    });

    const user = existingByProvider
      ? await this.prisma.user.update({
          where: { id: existingByProvider.id },
          data: {
            email: profile.email,
            nickname: profile.nickname,
            profileImageUrl:
              profile.profileImageUrl ?? existingByProvider.profileImageUrl,
          },
        })
      : await this.createOAuthUser(profile);

    if (user.deletedAt) {
      throw new UnauthorizedException('탈퇴한 계정입니다.');
    }

    return this.issueTokenPair(user);
  }

  async refresh(refreshToken: string) {
    if (!refreshToken) {
      throw new UnauthorizedException('Refresh token is required.');
    }

    const tokenHash = this.hashToken(refreshToken);
    const stored = await this.prisma.refreshToken.findUnique({
      where: { tokenHash },
      include: { user: true },
    });

    if (
      !stored ||
      stored.revokedAt ||
      stored.expiresAt <= new Date() ||
      stored.user.deletedAt
    ) {
      throw new UnauthorizedException('Invalid refresh token.');
    }

    const nextRefreshToken = this.createRefreshToken();
    const nextRefreshTokenHash = this.hashToken(nextRefreshToken);
    const nextExpiresAt = this.refreshTokenExpiresAt();

    await this.prisma.$transaction(async (tx) => {
      const revoked = await tx.refreshToken.updateMany({
        where: { id: stored.id, revokedAt: null },
        data: { revokedAt: new Date() },
      });

      if (revoked.count !== 1) {
        throw new UnauthorizedException('Invalid refresh token.');
      }

      await tx.refreshToken.create({
        data: {
          userId: stored.userId,
          tokenHash: nextRefreshTokenHash,
          expiresAt: nextExpiresAt,
        },
      });
    });

    return {
      accessToken: this.issueAccessToken(stored.user),
      refreshToken: nextRefreshToken,
    };
  }

  async logout(refreshToken?: string) {
    if (refreshToken) {
      await this.prisma.refreshToken.updateMany({
        where: {
          tokenHash: this.hashToken(refreshToken),
          revokedAt: null,
        },
        data: { revokedAt: new Date() },
      });
    }

    return { message: 'Logged out.' };
  }

  async forgotPassword(email: string) {
    const user = await this.prisma.user.findUnique({ where: { email } });

    // 이메일 존재 여부를 노출하지 않기 위해 항상 같은 응답 반환
    if (!user || user.deletedAt || user.provider !== Provider.LOCAL) {
      return { message: '이메일로 재설정 코드를 전송했습니다.' };
    }

    const code = Math.floor(100000 + Math.random() * 900000).toString();
    const expiresAt = new Date(Date.now() + PASSWORD_RESET_TTL_MINUTES * 60 * 1000);

    await this.prisma.passwordResetToken.updateMany({
      where: { userId: user.id, usedAt: null },
      data: { usedAt: new Date() },
    });

    await this.prisma.passwordResetToken.create({
      data: {
        userId: user.id,
        tokenHash: this.hashToken(code),
        expiresAt,
      },
    });

    await this.mailService.sendPasswordResetCode(email, user.nickname, code);
    return { message: '이메일로 재설정 코드를 전송했습니다.' };
  }

  async resetPassword(email: string, code: string, newPassword: string) {
    const user = await this.prisma.user.findUnique({ where: { email } });

    if (!user || user.deletedAt || user.provider !== Provider.LOCAL) {
      throw new UnauthorizedException('유효하지 않은 요청입니다.');
    }

    const now = new Date();
    const token = await this.prisma.passwordResetToken.findFirst({
      where: {
        userId: user.id,
        tokenHash: this.hashToken(code),
        usedAt: null,
        expiresAt: { gt: now },
      },
    });

    if (!token) {
      throw new UnauthorizedException('유효하지 않거나 만료된 코드입니다.');
    }

    const hashed = await bcrypt.hash(newPassword, 10);

    await this.prisma.$transaction(async (tx) => {
      const consumed = await tx.passwordResetToken.updateMany({
        where: { id: token.id, usedAt: null, expiresAt: { gt: new Date() } },
        data: { usedAt: new Date() },
      });
      if (consumed.count !== 1) {
        throw new UnauthorizedException('유효하지 않거나 만료된 코드입니다.');
      }

      await tx.user.update({
        where: { id: user.id },
        data: { password: hashed },
      });
      await tx.refreshToken.updateMany({
        where: { userId: user.id, revokedAt: null },
        data: { revokedAt: new Date() },
      });
    });

    return { message: '비밀번호가 재설정되었습니다.' };
  }

  private async createOAuthUser(profile: OAuthProfile) {
    const existingByEmail = await this.prisma.user.findUnique({
      where: { email: profile.email },
    });

    if (!existingByEmail) {
      return this.prisma.user.create({
        data: {
          email: profile.email,
          nickname: profile.nickname,
          profileImageUrl: profile.profileImageUrl,
          provider: profile.provider,
          providerId: profile.providerId,
        },
      });
    }

    throw new ConflictException(
      '이미 가입된 이메일입니다. 기존 계정으로 로그인한 뒤 계정을 연결해주세요.',
    );
  }

  private async getKakaoProfile(dto: OAuthLoginDto): Promise<OAuthProfile> {
    const clientId = this.requiredEnv('KAKAO_REST_API_KEY');
    const clientSecret = process.env.KAKAO_CLIENT_SECRET;
    const tokenParams = new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: clientId,
      redirect_uri: dto.redirectUri,
      code: dto.code,
      ...(clientSecret ? { client_secret: clientSecret } : {}),
    });

    const token = await this.fetchJson<{
      access_token?: string;
      error_description?: string;
    }>('https://kauth.kakao.com/oauth/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: tokenParams.toString(),
    });

    if (!token.access_token) {
      throw new UnauthorizedException(
        token.error_description ?? '카카오 인증에 실패했습니다.',
      );
    }

    const me = await this.fetchJson<{
      id: number | string;
      kakao_account?: {
        email?: string;
        profile?: { nickname?: string; profile_image_url?: string };
      };
    }>('https://kapi.kakao.com/v2/user/me', {
      headers: { Authorization: `Bearer ${token.access_token}` },
    });

    const providerId = String(me.id);
    return {
      provider: Provider.KAKAO,
      providerId,
      email: me.kakao_account?.email ?? `kakao_${providerId}@oauth.local`,
      nickname: me.kakao_account?.profile?.nickname ?? '카카오 사용자',
      profileImageUrl: me.kakao_account?.profile?.profile_image_url,
    };
  }

  private async getNaverProfile(dto: OAuthLoginDto): Promise<OAuthProfile> {
    const clientId = this.requiredEnv('NAVER_CLIENT_ID');
    const clientSecret = this.requiredEnv('NAVER_CLIENT_SECRET');
    const tokenParams = new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: clientId,
      client_secret: clientSecret,
      code: dto.code,
      ...(dto.state ? { state: dto.state } : {}),
    });

    const token = await this.fetchJson<{
      access_token?: string;
      error_description?: string;
    }>(`https://nid.naver.com/oauth2.0/token?${tokenParams.toString()}`);

    if (!token.access_token) {
      throw new UnauthorizedException(
        token.error_description ?? '네이버 인증에 실패했습니다.',
      );
    }

    const me = await this.fetchJson<{
      response?: {
        id?: string;
        email?: string;
        nickname?: string;
        profile_image?: string;
      };
    }>('https://openapi.naver.com/v1/nid/me', {
      headers: { Authorization: `Bearer ${token.access_token}` },
    });

    const providerId = me.response?.id;
    if (!providerId) {
      throw new UnauthorizedException('네이버 사용자 정보를 가져오지 못했습니다.');
    }

    return {
      provider: Provider.NAVER,
      providerId,
      email: me.response?.email ?? `naver_${providerId}@oauth.local`,
      nickname: me.response?.nickname ?? '네이버 사용자',
      profileImageUrl: me.response?.profile_image,
    };
  }

  private requiredEnv(name: string) {
    const value = process.env[name];
    if (!value) {
      throw new Error(`${name} environment variable is required.`);
    }
    return value;
  }

  private async issueTokenPair(user: { id: string; email: string }) {
    const refreshToken = this.createRefreshToken();
    await this.prisma.refreshToken.create({
      data: {
        userId: user.id,
        tokenHash: this.hashToken(refreshToken),
        expiresAt: this.refreshTokenExpiresAt(),
      },
    });

    return {
      accessToken: this.issueAccessToken(user),
      refreshToken,
    };
  }

  private issueAccessToken(user: { id: string; email: string }) {
    return this.jwtService.sign({ sub: user.id, email: user.email });
  }

  private createRefreshToken() {
    return randomBytes(48).toString('base64url');
  }

  private hashToken(token: string) {
    return createHash('sha256').update(token).digest('hex');
  }

  private refreshTokenExpiresAt() {
    const expiresAt = new Date();
    expiresAt.setDate(expiresAt.getDate() + REFRESH_TOKEN_TTL_DAYS);
    return expiresAt;
  }

  private async fetchJson<T>(url: string, init?: RequestInit): Promise<T> {
    const response = await fetch(url, init);
    const data = (await response.json()) as T & {
      error?: string;
      error_description?: string;
      msg?: string;
      message?: string;
    };
    if (!response.ok) {
      throw new UnauthorizedException(
        data.error_description ??
          data.msg ??
          data.message ??
          data.error ??
          '간편 로그인 인증에 실패했습니다.',
      );
    }
    return data;
  }
}
