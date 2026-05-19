import { Module } from '@nestjs/common';
import { PlacesController } from './places.controller';
import { PlacesService } from './places.service';
import { KakaoPlaceSearchService } from './kakao/kakao-place-search.service';
import { UploadModule } from '../upload/upload.module';
import { NotificationsModule } from '../notifications/notifications.module';
import { MapsModule } from '../maps/maps.module';

@Module({
  imports: [UploadModule, NotificationsModule, MapsModule],
  controllers: [PlacesController],
  providers: [PlacesService, KakaoPlaceSearchService],
})
export class PlacesModule {}
