package com.loco.maps;

import java.security.SecureRandom;
import java.util.function.Supplier;
import com.loco.web.ApiProblem;
import org.springframework.stereotype.Component;

@Component
public class InviteCodes {
    private static final String ALPHABET="0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private final MapRepository maps;
    private final Supplier<String> candidate;
    @org.springframework.beans.factory.annotation.Autowired
    public InviteCodes(MapRepository maps) {
        this.maps=maps;
        var random=new SecureRandom();
        candidate=()-> {
            var value=new StringBuilder(8);
            for(int i=0;i<8;i++) value.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            return value.toString();
        };
    }
    InviteCodes(MapRepository maps,Supplier<String> candidate) { this.maps=maps; this.candidate=candidate; }
    public String generate(String previous) {
        for(int attempt=0;attempt<10;attempt++) {
            String code=candidate.get();
            if(!code.equals(previous)&&!maps.existsByInviteCode(code)) return code;
        }
        throw ApiProblem.badRequest("초대 코드 생성에 실패했습니다. 다시 시도해주세요.");
    }
}
