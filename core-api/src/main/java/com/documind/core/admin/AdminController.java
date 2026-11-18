package com.documind.core.admin;

import com.documind.core.chat.MessageRepository;
import com.documind.core.common.ApiException;
import com.documind.core.document.DocumentRepository;
import com.documind.core.user.User;
import com.documind.core.user.UserRepository;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** FR-5: user management and usage. Shows metadata only, never document contents. */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminController {

    public record AdminUser(UUID id, String name, String email, String role, boolean enabled, Instant createdAt,
                            long documents, long questions) {}

    public record UpdateUserRequest(@NotNull Boolean enabled) {}

    private final UserRepository users;
    private final DocumentRepository documents;
    private final MessageRepository messages;

    public AdminController(UserRepository users, DocumentRepository documents, MessageRepository messages) {
        this.users = users;
        this.documents = documents;
        this.messages = messages;
    }

    @GetMapping
    public List<AdminUser> list() {
        Map<UUID, Long> docCounts = toMap(documents.countPerUser());
        Map<UUID, Long> questionCounts = toMap(messages.countQuestionsPerUser());
        return users.findAll(Sort.by("createdAt").descending()).stream()
                .map(u -> toDto(u, docCounts.getOrDefault(u.getId(), 0L), questionCounts.getOrDefault(u.getId(), 0L)))
                .toList();
    }

    @PatchMapping("/{id}")
    @Transactional
    public AdminUser update(@PathVariable UUID id, @RequestBody UpdateUserRequest req) {
        User user = users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
        if (req.enabled() != null) {
            user.setEnabled(req.enabled());
        }
        return toDto(user, 0, 0);
    }

    private static AdminUser toDto(User u, long docs, long questions) {
        return new AdminUser(u.getId(), u.getName(), u.getEmail(), u.getRole().name(), u.isEnabled(),
                u.getCreatedAt(), docs, questions);
    }

    private static Map<UUID, Long> toMap(List<Object[]> rows) {
        return rows.stream().collect(Collectors.toMap(r -> (UUID) r[0], r -> (Long) r[1]));
    }
}
