package com.loco.places;

import com.loco.images.LocalImageFiles;
import com.loco.images.LocalImageStorage;
import com.loco.web.ApiProblem;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PlaceImageService {
  private final PlaceImageRepository images;
  private final PlaceQueryRepository queries;
  private final LocalImageStorage storage;
  private final LocalImageFiles files;
  private final TransactionTemplate transaction;
  private final Clock clock;

  public PlaceImageService(
      PlaceImageRepository images,
      PlaceQueryRepository queries,
      LocalImageStorage storage,
      LocalImageFiles files,
      TransactionTemplate transaction,
      Clock clock) {
    this.images = images;
    this.queries = queries;
    this.storage = storage;
    this.files = files;
    this.transaction = transaction;
    this.clock = clock;
  }

  public PlaceResponses.Core upload(String id, String user, MultipartFile file) {
    if (file == null) throw ApiProblem.badRequest("이미지 파일을 첨부해주세요.");
    requireRoom(images.find(id), user);
    // 변환은 DB 잠금 밖에서 하고, 정원은 실제 UPDATE에서 다시 검사한다.
    String url = storage.save(file, LocalImageStorage.Purpose.PLACE);
    try {
      return transaction.execute(
          status -> {
            if (images.append(id, user, url, LocalDateTime.now(clock)) == 0) {
              requireRoom(images.find(id), user);
              throw new IllegalStateException("Image append did not update a valid place");
            }
            return queries.core(id);
          });
    } catch (RuntimeException exception) {
      files.delete(url);
      throw exception;
    }
  }

  private void requireRoom(PlaceImageRepository.State state, String user) {
    if (!state.authorId().equals(user)) throw ApiProblem.forbidden("권한이 없습니다.");
    if (state.urls().length >= 3) throw ApiProblem.badRequest("최대 3장까지 업로드 가능합니다.");
  }

  private record Removal(PlaceResponses.Core response, boolean removed) {}

  public PlaceResponses.Core delete(
      String id, String user, com.fasterxml.jackson.databind.JsonNode body) {
    var value =
        body == null
            ? com.fasterxml.jackson.databind.node.MissingNode.getInstance()
            : body.path("imageUrl");
    if (value.isMissingNode()
        || value.isNull()
        || (value.isBoolean() && !value.booleanValue())
        || (value.isNumber() && value.doubleValue() == 0)
        || (value.isTextual() && value.asText().isEmpty()))
      throw ApiProblem.badRequest("삭제할 이미지가 필요합니다.");
    var place = images.find(id);
    if (!place.authorId().equals(user)) throw ApiProblem.forbidden("이미지 삭제 권한이 없습니다.");
    // v2는 DTO 없이 truthy 비문자 값도 받으며, 포함되지 않은 URL은 갱신하지 않는다.
    if (!value.isTextual() || !java.util.Arrays.asList(place.urls()).contains(value.asText()))
      return queries.core(id);
    String url = value.asText();
    var result =
        transaction.execute(
            status -> {
              int changed = images.remove(id, user, url, LocalDateTime.now(clock));
              if (changed == 0) {
                var current = images.find(id);
                if (!current.authorId().equals(user))
                  throw ApiProblem.forbidden("이미지 삭제 권한이 없습니다.");
              }
              return new Removal(queries.core(id), changed > 0);
            });
    if (result.removed()) files.delete(url);
    return result.response();
  }
}
