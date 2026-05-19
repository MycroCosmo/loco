import {
  BadRequestException,
  Controller,
  Get,
  Post,
  Put,
  Delete,
  Body,
  Param,
  Query,
  UseGuards,
  Request,
  UseInterceptors,
  UploadedFile,
} from '@nestjs/common';
import { FileInterceptor } from '@nestjs/platform-express';
import { Visibility } from '@prisma/client';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { MapsService } from './maps.service';
import { CreateMapDto } from './dto/create-map.dto';
import { JoinMapByCodeDto } from './dto/join-map-by-code.dto';
import { UpdateMapDto } from './dto/update-map.dto';
import { imageUploadOptions } from '../upload/image-upload.options';

@Controller('maps')
@UseGuards(JwtAuthGuard)
export class MapsController {
  constructor(private readonly mapsService: MapsService) {}

  @Get()
  findAll(
    @Request() req,
    @Query('cursor') cursor?: string,
    @Query('limit') limit?: string,
    @Query('visibility') visibility?: string,
    @Query('sort') sort?: string,
    @Query('search') search?: string,
  ) {
    return this.mapsService.findAll(req.user.userId, {
      cursor,
      limit: limit ? parseInt(limit, 10) : undefined,
      visibility: this.parseVisibilityFilter(visibility),
      sort: this.parseSort(sort),
      search,
    });
  }

  @Get('me')
  findMine(@Request() req, @Query('type') type?: 'owned' | 'joined') {
    return this.mapsService.findMine(req.user.userId, type ?? 'owned');
  }

  @Post()
  create(@Request() req, @Body() dto: CreateMapDto) {
    return this.mapsService.create(req.user.userId, dto);
  }

  @Post('join-by-code')
  joinByCode(@Request() req, @Body() dto: JoinMapByCodeDto) {
    return this.mapsService.joinByCode(dto.inviteCode, req.user.userId);
  }

  @Get(':mapId')
  findOne(@Request() req, @Param('mapId') mapId: string) {
    return this.mapsService.findOne(mapId, req.user.userId);
  }

  @Get(':mapId/membership')
  getMembership(@Request() req, @Param('mapId') mapId: string) {
    return this.mapsService.getMembership(mapId, req.user.userId);
  }

  @Post(':mapId/join')
  join(@Request() req, @Param('mapId') mapId: string) {
    return this.mapsService.join(mapId, req.user.userId);
  }

  @Post(':mapId/leave')
  leave(@Request() req, @Param('mapId') mapId: string) {
    return this.mapsService.leave(mapId, req.user.userId);
  }

  @Post(':mapId/invite-code/regenerate')
  regenerateInviteCode(@Request() req, @Param('mapId') mapId: string) {
    return this.mapsService.regenerateInviteCode(mapId, req.user.userId);
  }

  @Put(':mapId')
  update(
    @Request() req,
    @Param('mapId') mapId: string,
    @Body() dto: UpdateMapDto,
  ) {
    return this.mapsService.update(mapId, req.user.userId, dto);
  }

  @Delete(':mapId')
  remove(
    @Request() req,
    @Param('mapId') mapId: string,
    @Query('force') force?: string,
  ) {
    return this.mapsService.remove(mapId, req.user.userId, force === 'true');
  }

  @Get(':mapId/members')
  getMembers(@Request() req, @Param('mapId') mapId: string) {
    return this.mapsService.getMembers(mapId, req.user.userId);
  }

  @Delete(':mapId/members/:userId')
  removeMember(
    @Request() req,
    @Param('mapId') mapId: string,
    @Param('userId') targetUserId: string,
  ) {
    return this.mapsService.removeMember(mapId, targetUserId, req.user.userId);
  }

  @Post(':mapId/cover')
  @UseInterceptors(FileInterceptor('file', imageUploadOptions))
  uploadCover(
    @Request() req,
    @Param('mapId') mapId: string,
    @UploadedFile() file: Express.Multer.File,
  ) {
    return this.mapsService.updateCoverImage(mapId, req.user.userId, file);
  }

  private parseVisibilityFilter(visibility?: string) {
    if (!visibility) return undefined;
    if (visibility === Visibility.PUBLIC || visibility === Visibility.PRIVATE) {
      return visibility;
    }
    throw new BadRequestException('지원하지 않는 공개 설정입니다.');
  }

  private parseSort(sort?: string) {
    if (!sort || sort === 'latest') return 'latest';
    if (sort === 'popular' || sort === 'places') return sort;
    throw new BadRequestException('sort는 latest, popular, places만 가능합니다.');
  }
}
