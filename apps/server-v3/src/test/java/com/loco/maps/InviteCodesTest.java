package com.loco.maps;

import java.util.ArrayDeque;
import java.util.List;
import com.loco.web.ApiProblem;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InviteCodesTest {
    @Test void skipsPreviousAndStoredCodes() {
        var maps=mock(MapRepository.class);
        when(maps.existsByInviteCode("STORED01")).thenReturn(true);
        var candidates=new ArrayDeque<>(List.of("PREVIOUS","STORED01","FRESH001"));
        assertThat(new InviteCodes(maps,candidates::remove).generate("PREVIOUS")).isEqualTo("FRESH001");
    }
    @Test void exhaustionIsTheV2BadRequest() {
        var maps=mock(MapRepository.class);when(maps.existsByInviteCode(anyString())).thenReturn(true);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var codes=new InviteCodes(maps,()->{calls.incrementAndGet();return "EXISTING";});
        var error=catchThrowableOfType(()->codes.generate(null),ApiProblem.class);
        assertThat(error.status()).isEqualTo(400);assertThat(error.body().get("message")).isEqualTo("초대 코드 생성에 실패했습니다. 다시 시도해주세요.");
        assertThat(calls.get()).isEqualTo(10);
    }
    @Test void productionCodesHaveChosenFormat() {
        assertThat(new InviteCodes(mock(MapRepository.class)).generate(null)).matches("[A-Z0-9]{8}");
    }
}
