import { Injectable, BadRequestException } from '@nestjs/common';
import * as path from 'path';
import * as fs from 'fs/promises';
import * as crypto from 'crypto';

// eslint-disable-next-line @typescript-eslint/no-require-imports
const sharp = require('sharp') as typeof import('sharp');

const allowedImageFormats = new Set(['jpeg', 'png', 'webp', 'heif', 'avif']);

@Injectable()
export class UploadService {
  private readonly uploadDir = path.resolve(process.cwd(), 'uploads');

  async saveAsWebP(
    buffer: Buffer,
    subdir: string,
    options: { width: number; height: number; fit?: 'cover' | 'inside' },
  ): Promise<string> {
    if (!buffer || buffer.length === 0) {
      throw new BadRequestException('빈 파일입니다.');
    }

    const image = sharp(buffer, { failOn: 'warning' });
    let metadata: import('sharp').Metadata;

    try {
      metadata = await image.metadata();
    } catch {
      throw new BadRequestException('지원하지 않는 이미지 파일입니다.');
    }

    if (!metadata.format || !allowedImageFormats.has(metadata.format)) {
      throw new BadRequestException('지원하지 않는 이미지 형식입니다.');
    }

    const dir = path.join(this.uploadDir, subdir);
    await fs.mkdir(dir, { recursive: true });

    const filename = `${crypto.randomUUID()}.webp`;
    const filepath = path.join(dir, filename);

    await image
      .resize(options.width, options.height, {
        fit: options.fit ?? 'inside',
        withoutEnlargement: true,
      })
      .webp({ quality: 80 })
      .toFile(filepath);

    return `/uploads/${subdir}/${filename}`;
  }

  async deleteFile(relativePath: string): Promise<void> {
    try {
      const filepath = path.resolve(
        process.cwd(),
        relativePath.replace(/^\/+/, ''),
      );
      const uploadRoot = path.resolve(this.uploadDir);

      if (!filepath.startsWith(uploadRoot + path.sep)) {
        return;
      }

      await fs.unlink(filepath);
    } catch {
      // Missing files are ignored so DB cleanup can proceed.
    }
  }
}
