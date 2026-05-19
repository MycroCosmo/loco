import 'package:http_parser/http_parser.dart';
import 'package:image_picker/image_picker.dart';

MediaType imageContentType(XFile imageFile) {
  final mimeType = imageFile.mimeType;
  if (mimeType != null && mimeType.contains('/')) {
    return MediaType.parse(mimeType);
  }

  final name = imageFile.name.toLowerCase();
  if (name.endsWith('.png')) return MediaType('image', 'png');
  if (name.endsWith('.webp')) return MediaType('image', 'webp');
  if (name.endsWith('.heic')) return MediaType('image', 'heic');
  if (name.endsWith('.heif')) return MediaType('image', 'heif');
  if (name.endsWith('.avif')) return MediaType('image', 'avif');
  return MediaType('image', 'jpeg');
}
