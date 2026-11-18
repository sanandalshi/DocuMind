package com.documind.core.chat;

import java.util.UUID;

public record Citation(int index, UUID documentId, String documentName, int page, String excerpt, double score) {}
