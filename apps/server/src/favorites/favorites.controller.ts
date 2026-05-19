import {
  Controller,
  Get,
  Post,
  Delete,
  Body,
  Param,
  UseGuards,
  Request,
} from '@nestjs/common';
import { JwtAuthGuard } from '../auth/guards/jwt-auth.guard';
import { FavoritesService } from './favorites.service';
import { CreateFavoriteDto } from './dto/create-favorite.dto';

@Controller('favorites')
@UseGuards(JwtAuthGuard)
export class FavoritesController {
  constructor(private readonly favoritesService: FavoritesService) {}

  @Get()
  findAll(@Request() req) {
    return this.favoritesService.findMyFavorites(req.user.userId);
  }

  @Post()
  create(@Request() req, @Body() dto: CreateFavoriteDto) {
    return this.favoritesService.addFavorite(req.user.userId, dto);
  }

  @Delete(':favoriteId')
  remove(@Request() req, @Param('favoriteId') favoriteId: string) {
    return this.favoritesService.removeFavorite(favoriteId, req.user.userId);
  }
}
