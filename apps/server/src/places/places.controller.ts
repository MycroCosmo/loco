import {
  Controller,
  BadRequestException,
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
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { PlacesService } from './places.service';
import { KakaoPlaceSearchService } from './kakao/kakao-place-search.service';
import { CreatePlaceDto } from './dto/create-place.dto';
import { UpdatePlaceDto } from './dto/update-place.dto';
import { SearchPlaceDto } from './dto/search-place.dto';
import { CreatePlaceReviewDto } from './dto/create-place-review.dto';
import { imageUploadOptions } from '../upload/image-upload.options';

@Controller()
@UseGuards(JwtAuthGuard)
export class PlacesController {
  constructor(
    private readonly placesService: PlacesService,
    private readonly kakaoSearch: KakaoPlaceSearchService,
  ) {}

  @Get('places/search')
  searchKakaoPlaces(@Query() dto: SearchPlaceDto) {
    return this.kakaoSearch.searchByKeyword(dto.query, {
      x: dto.x ? parseFloat(dto.x) : undefined,
      y: dto.y ? parseFloat(dto.y) : undefined,
      radius: dto.radius ? parseInt(dto.radius) : undefined,
      page: dto.page ? parseInt(dto.page) : undefined,
      size: dto.size ? parseInt(dto.size) : undefined,
    });
  }

  @Get('places/me')
  findMyPlaces(@Request() req) {
    return this.placesService.findMyPlaces(req.user.userId);
  }

  @Get('places/reviews/me')
  findMyReviews(@Request() req) {
    return this.placesService.findMyReviews(req.user.userId);
  }

  @Get('maps/:mapId/places')
  findAll(
    @Request() req,
    @Param('mapId') mapId: string,
    @Query('cursor') cursor?: string,
    @Query('limit') limit?: string,
    @Query('sort') sort?: string,
    @Query('search') search?: string,
    @Query('tag') tag?: string,
  ) {
    return this.placesService.findAllByMap(mapId, req.user.userId, {
      cursor,
      limit: limit ? parseInt(limit, 10) : undefined,
      sort: this.parsePlaceSort(sort),
      search,
      tag,
    });
  }

  @Get('maps/:mapId/places/markers')
  findMarkers(
    @Request() req,
    @Param('mapId') mapId: string,
    @Query('swLat') swLat: string,
    @Query('swLng') swLng: string,
    @Query('neLat') neLat: string,
    @Query('neLng') neLng: string,
    @Query('level') level?: string,
  ) {
    let parsedLevel: number | undefined;
    if (level) {
      parsedLevel = Number(level);
      if (
        !Number.isInteger(parsedLevel) ||
        parsedLevel < 1 ||
        parsedLevel > 14
      ) {
        throw new BadRequestException('level은 숫자여야 합니다.');
      }
    }

    return this.placesService.findMarkersByMap(mapId, req.user.userId, {
      swLat: parseFloat(swLat),
      swLng: parseFloat(swLng),
      neLat: parseFloat(neLat),
      neLng: parseFloat(neLng),
      level: parsedLevel,
    });
  }

  @Get('maps/:mapId/places/marker-group')
  findMarkerGroup(
    @Request() req,
    @Param('mapId') mapId: string,
    @Query('lat') lat: string,
    @Query('lng') lng: string,
    @Query('groupKey') groupKey?: string,
  ) {
    return this.placesService.findMarkerGroup(
      mapId,
      req.user.userId,
      parseFloat(lat),
      parseFloat(lng),
      groupKey,
    );
  }

  @Get('maps/:mapId/places/marker-cluster')
  findMarkerCluster(
    @Request() req,
    @Param('mapId') mapId: string,
    @Query('precision') precision: string,
    @Query('cellLatKey') cellLatKey: string,
    @Query('cellLngKey') cellLngKey: string,
    @Query('limit') limit?: string,
  ) {
    const parsedPrecision = Number(precision);
    if (
      !Number.isInteger(parsedPrecision) ||
      ![1, 2, 3].includes(parsedPrecision)
    ) {
      throw new BadRequestException('precision은 1, 2, 3 중 하나여야 합니다.');
    }

    return this.placesService.findMarkerCluster(mapId, req.user.userId, {
      precision: parsedPrecision,
      cellLatKey,
      cellLngKey,
      limit: limit ? parseInt(limit, 10) : undefined,
    });
  }

  @Post('maps/:mapId/places')
  create(
    @Request() req,
    @Param('mapId') mapId: string,
    @Body() dto: CreatePlaceDto,
  ) {
    return this.placesService.create(mapId, req.user.userId, dto);
  }

  @Put('places/:placeId')
  update(
    @Request() req,
    @Param('placeId') placeId: string,
    @Body() dto: UpdatePlaceDto,
  ) {
    return this.placesService.update(placeId, req.user.userId, dto);
  }

  @Delete('places/:placeId')
  remove(@Request() req, @Param('placeId') placeId: string) {
    return this.placesService.remove(placeId, req.user.userId);
  }

  @Get('places/:placeId')
  findOne(@Request() req, @Param('placeId') placeId: string) {
    return this.placesService.findOne(placeId, req.user.userId);
  }

  @Get('places/:placeId/reviews')
  getReviews(@Request() req, @Param('placeId') placeId: string) {
    return this.placesService.getReviews(placeId, req.user.userId);
  }

  @Post('places/:placeId/reviews')
  submitReview(
    @Request() req,
    @Param('placeId') placeId: string,
    @Body() dto: CreatePlaceReviewDto,
  ) {
    return this.placesService.submitReview(placeId, req.user.userId, dto);
  }

  @Delete('places/:placeId/reviews/me')
  deleteMyReview(@Request() req, @Param('placeId') placeId: string) {
    return this.placesService.deleteMyReview(placeId, req.user.userId);
  }

  @Post('places/:placeId/images')
  @UseInterceptors(FileInterceptor('file', imageUploadOptions))
  uploadPlaceImage(
    @Request() req,
    @Param('placeId') placeId: string,
    @UploadedFile() file: Express.Multer.File,
  ) {
    return this.placesService.addImage(placeId, req.user.userId, file);
  }

  @Delete('places/:placeId/images')
  deletePlaceImage(
    @Request() req,
    @Param('placeId') placeId: string,
    @Body('imageUrl') imageUrl: string,
  ) {
    return this.placesService.removeImage(placeId, req.user.userId, imageUrl);
  }

  private parsePlaceSort(sort?: string) {
    if (!sort || sort === 'latest') return 'latest';
    if (sort === 'name' || sort === 'reviews') return sort;
    throw new BadRequestException('sort는 latest, name, reviews만 가능합니다.');
  }
}
