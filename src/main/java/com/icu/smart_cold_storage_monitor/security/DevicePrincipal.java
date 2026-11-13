package com.icu.smart_cold_storage_monitor.security;

/** Principal of a request authenticated with a device credential (role DEVICE, ingestion only). */
public record DevicePrincipal(String deviceId) {
}
