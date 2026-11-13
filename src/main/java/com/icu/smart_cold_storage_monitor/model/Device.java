package com.icu.smart_cold_storage_monitor.model;

/** A monitored sensor device (row of {@code devices}); {@code id} is the identifier the device sends. */
public record Device(String id, String name, boolean enabled) {
}
