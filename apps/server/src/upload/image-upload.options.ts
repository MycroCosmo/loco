import { BadRequestException } from '@nestjs/common';
import { MulterOptions } from '@nestjs/platform-express/multer/interfaces/multer-options.interface';

const maxImageUploadSize = 10 * 1024 * 1024;
const allowedMimeTypes = new Set([
  'image/jpeg',
  'image/png',
  'image/webp',
  'image/heic',
  'image/heif',
  'image/avif',
]);

export const imageUploadOptions: MulterOptions = {
  limits: {
    fileSize: maxImageUploadSize,
  },
  fileFilter: (_req, file, callback) => {
    if (!allowedMimeTypes.has(file.mimetype)) {
      callback(new BadRequestException('지원하지 않는 이미지 형식입니다.'), false);
      return;
    }

    callback(null, true);
  },
};
