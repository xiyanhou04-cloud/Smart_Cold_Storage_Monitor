package com.icu.smart_cold_storage_monitor.simulator;

import tools.jackson.databind.ObjectMapper;

/** Dry-run sender: accepts every reading and returns the JSON that would have been posted. */
final class ConsoleReadingSender implements ReadingSender {
    private final ObjectMapper mapper;

    ConsoleReadingSender(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public SendResult send(ReadingPayload payload) {
        return new SendResult(Outcome.ACCEPTED, 0, "DRY_RUN " + mapper.writeValueAsString(payload));
    }
}
