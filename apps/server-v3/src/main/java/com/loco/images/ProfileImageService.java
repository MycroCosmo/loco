package com.loco.images;

import java.time.Clock;
import java.time.LocalDateTime;
import com.loco.users.UserInputs;
import com.loco.users.UserRepository;
import com.loco.users.UserResponses;
import com.loco.web.ApiProblem;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ProfileImageService {
    private final UserRepository users;
    private final ProfileImageStorage storage;
    private final TransactionTemplate transaction;
    private final Clock clock;
    public ProfileImageService(UserRepository users, ProfileImageStorage storage, TransactionTemplate transaction, Clock clock) {
        this.users = users; this.storage = storage; this.transaction = transaction; this.clock = clock;
    }
    public UserResponses.Image update(String id, MultipartFile file) {
        if (file == null) throw ApiProblem.badRequest("이미지 파일을 첨부해주세요.");
        String previous = users.findById(id).orElseThrow(() -> ApiProblem.notFound("사용자를 찾을 수 없습니다.")).getProfileImageUrl();
        String image = storage.save(file);
        try {
            var response = transaction.execute(status -> {
                var user = users.findById(id).orElseThrow(() -> ApiProblem.notFound("사용자를 찾을 수 없습니다."));
                user.updateProfile(new UserInputs.Update(false, null, true, image), LocalDateTime.now(clock));
                users.flush();
                return UserResponses.image(user);
            });
            storage.delete(previous);
            return response;
        } catch (RuntimeException exception) {
            storage.delete(image); throw exception;
        }
    }
}
