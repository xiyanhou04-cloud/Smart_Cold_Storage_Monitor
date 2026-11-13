package com.icu.smart_cold_storage_monitor.security;

import com.icu.smart_cold_storage_monitor.model.Device;
import com.icu.smart_cold_storage_monitor.repository.DeviceRepository;
import com.icu.smart_cold_storage_monitor.web.JsonResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the device-ingestion filter chain: the {@code Authorization: Device <deviceId>:<token>} header must
 * carry a valid token for an existing, enabled device. Otherwise it answers 401 {@code INVALID_DEVICE_CREDENTIAL}
 * and the request goes no further. Session cookies are never consulted.
 *
 * <p>Not a Spring bean on purpose: as a bean, Spring Boot would also register it for every servlet request.
 */
public class DeviceAuthenticationFilter extends OncePerRequestFilter {

    public static final String ROLE = "DEVICE";

    private final DeviceCredentialService credentials;
    private final DeviceRepository devices;
    private final JsonResponseWriter writer;

    public DeviceAuthenticationFilter(DeviceCredentialService credentials, DeviceRepository devices,
                                      JsonResponseWriter writer) {
        this.credentials = credentials;
        this.devices = devices;
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Device> device = credentials.verify(request.getHeader(HttpHeaders.AUTHORIZATION))
                .flatMap(devices::findById)
                .filter(Device::enabled);
        if (device.isEmpty()) {
            SecurityContextHolder.clearContext();
            writer.writeError(request, response, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_DEVICE_CREDENTIAL",
                    "Missing or invalid device credential, or the device is unknown or disabled.");
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new DevicePrincipal(device.get().id()), null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE))));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
