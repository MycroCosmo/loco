package com.loco.mail;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class PasswordResetMail {
    private final JavaMailSender sender;
    private final String from;
    private final String template;
    public PasswordResetMail(JavaMailSender sender, @Value("${spring.mail.username}") String username) throws java.io.IOException {
        this.sender = sender;
        this.from = username.isEmpty() ? "noreply@loco.app" : username;
        this.template = new ClassPathResource("mail/password-reset.html").getContentAsString(StandardCharsets.UTF_8);
    }
    public void send(String to, String nickname, String code) {
        var message = sender.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from, "LOCO");
            helper.setTo(to);
            helper.setSubject("[LOCO] 비밀번호 재설정 인증 코드");
            helper.setText(template.replace("${code}", code).replace("${nickname}", nickname), true);
        } catch (Exception exception) { throw new MailPreparationException(exception); }
        sender.send(message);
    }
}
