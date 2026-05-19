import {
  Controller,
  Get,
  Put,
  Delete,
  Body,
  UseGuards,
  Request,
  Post,
  UseInterceptors,
  UploadedFile,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { UsersService } from './users.service';
import { UpdateUserDto } from './dto/update-user.dto';
import { imageUploadOptions } from '../upload/image-upload.options';

@Controller('users')
@UseGuards(JwtAuthGuard)
export class UsersController {
  constructor(private readonly usersService: UsersService) {}

  @Get('me')
  getMe(@Request() req) {
    return this.usersService.findMe(req.user.userId);
  }

  @Put('me')
  updateMe(@Request() req, @Body() dto: UpdateUserDto) {
    return this.usersService.updateMe(req.user.userId, dto);
  }

  @Post('me/profile-image')
  @UseInterceptors(FileInterceptor('file', imageUploadOptions))
  uploadProfileImage(
    @Request() req,
    @UploadedFile() file: Express.Multer.File,
  ) {
    return this.usersService.updateProfileImage(req.user.userId, file);
  }

  @Delete('me')
  deleteMe(@Request() req) {
    return this.usersService.deleteMe(req.user.userId);
  }

  @Put('me/fcm-token')
  updateFcmToken(@Request() req, @Body('fcmToken') fcmToken: string | null) {
    return this.usersService.updateFcmToken(req.user.userId, fcmToken ?? null);
  }
}
