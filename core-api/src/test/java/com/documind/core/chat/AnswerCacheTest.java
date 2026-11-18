package com.documind.core.chat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerCacheTest {

    private final UUID user = UUID.randomUUID();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void sameQuestionWithDifferentSpacingCaseAndOrderSharesAKey() {
        assertThat(AnswerCache.key(user, List.of(a, b), "How many leave days?"))
                .isEqualTo(AnswerCache.key(user, List.of(b, a), "  how many   LEAVE days "));
    }

    @Test
    void differentUsersOrDocumentSetsNeverShareAKey() {
        String key = AnswerCache.key(user, List.of(a), "q");
        assertThat(AnswerCache.key(UUID.randomUUID(), List.of(a), "q")).isNotEqualTo(key);
        assertThat(AnswerCache.key(user, List.of(a, b), "q")).isNotEqualTo(key);
    }
}
