package com.loco.maps;

import com.loco.images.LocalImageFiles;
import com.loco.images.LocalImageStorage;
import com.loco.web.ApiProblem;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MapCoverService {
  private final MapRepository maps;
  private final LocalImageStorage storage;
  private final LocalImageFiles files;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public MapCoverService(
      MapRepository maps,
      LocalImageStorage storage,
      LocalImageFiles files,
      TransactionTemplate transaction,
      Clock clock) {
    this.maps = maps;
    this.storage = storage;
    this.files = files;
    this.transaction = transaction;
    this.clock = clock;
  }

  public MapResponses.Core update(String id, String userId, MultipartFile file) {
    if (file == null) throw ApiProblem.badRequest("이미지 파일을 첨부해주세요.");
    var room = find(id);
    if (!room.getOwner().getId().equals(userId)) throw ApiProblem.forbidden("권한이 없습니다.");
    String previous = room.getCoverImageUrl();
    String image = storage.save(file, LocalImageStorage.Purpose.MAP_COVER);
    try {
      var response =
          transaction.execute(
              status -> {
                var current = find(id);
                current.updateCover(image, LocalDateTime.now(clock));
                maps.flush();
                return MapResponses.core(current);
              });
      files.delete(previous);
      return response;
    } catch (RuntimeException exception) {
      files.delete(image);
      throw exception;
    }
  }

  private MapRoom find(String id) {
    return maps.findById(id).orElseThrow(() -> ApiProblem.notFound("지도방을 찾을 수 없습니다."));
  }
}
