import { Module } from '@nestjs/common';
import { MapsController } from './maps.controller';
import { MapsService } from './maps.service';
import { MapPermissionsService } from './map-permissions.service';
import { UploadModule } from '../upload/upload.module';
import { NotificationsModule } from '../notifications/notifications.module';

@Module({
  imports: [UploadModule, NotificationsModule],
  controllers: [MapsController],
  providers: [MapsService, MapPermissionsService],
  exports: [MapPermissionsService],
})
export class MapsModule {}
