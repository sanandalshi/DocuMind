package com.documind.core.chat;

import com.documind.core.chat.ChatDtos.AskRequest;
import com.documind.core.chat.ChatDtos.ChatResponse;
import com.documind.core.chat.ChatDtos.CreateChatRequest;
import com.documind.core.chat.ChatDtos.MessageResponse;
import com.documind.core.security.AuthUser;
import com.documind.core.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chats")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChatResponse create(@CurrentUser AuthUser user, @RequestBody(required = false) CreateChatRequest req) {
        return ChatResponse.of(chatService.create(user.id(), req == null ? null : req.documentId()));
    }

    @GetMapping
    public List<ChatResponse> list(@CurrentUser AuthUser user) {
        return chatService.list(user.id()).stream().map(ChatResponse::of).toList();
    }

    @GetMapping("/{id}/messages")
    public List<MessageResponse> messages(@CurrentUser AuthUser user, @PathVariable UUID id) {
        return chatService.history(user.id(), id).stream().map(MessageResponse::of).toList();
    }

    @PostMapping(path = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ask(@CurrentUser AuthUser user, @PathVariable UUID id, @Valid @RequestBody AskRequest req) {
        return chatService.ask(user.id(), id, req.question());
    }
}
