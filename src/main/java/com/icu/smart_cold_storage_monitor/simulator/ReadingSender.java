package com.icu.smart_cold_storage_monitor.simulator;

/** Delivers one reading. Implementations report problems as a {@link SendResult}, never by throwing. */
interface ReadingSender {

    SendResult send(ReadingPayload payload);

    /** What the replay loop should do after a send. */
    enum Outcome {
        /** Stored, or recognized as an identical retry. */
        ACCEPTED,
        /** The receiver refused this reading (for example 400 or 409); continue with the next one. */
        REJECTED,
        /** Transient failure (network, timeout, 5xx); the same payload may be sent again. */
        RETRYABLE,
        /** This device cannot continue (for example its session is unknown). */
        STOP_DEVICE,
        /** No device can continue (bad credential, or the simulator was interrupted). */
        STOP_ALL
    }

    /**
     * @param status HTTP status, or 0 when no response was received
     * @param detail short description for the log; never contains credentials
     */
    record SendResult(Outcome outcome, int status, String detail) {
    }
}
