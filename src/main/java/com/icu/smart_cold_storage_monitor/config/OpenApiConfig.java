package com.icu.smart_cold_storage_monitor.config;

import com.icu.smart_cold_storage_monitor.controller.dto.CurrentUserResponse;
import com.icu.smart_cold_storage_monitor.web.ApiError;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI document ({@code /v3/api-docs}) and Swagger UI ({@code /swagger-ui.html}) for the browser API.
 *
 * <p>Login and logout are Spring Security filters rather than controller methods, so they are added here by hand.
 * Every operation is tagged with the security it needs: the {@code JSESSIONID} session cookie for signed-in
 * endpoints and the {@code X-CSRF-TOKEN} header for every non-GET request. The design document stays the source of
 * truth for the API contract; this document is generated from the code.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    static final String SESSION_SCHEME = "session";
    static final String CSRF_SCHEME = "csrf";
    static final String DEVICE_SCHEME = "device";

    /** Operations authenticated by a device credential instead of a session, and exempt from CSRF. */
    private static final Set<String> DEVICE_OPERATIONS = Set.of(
            "POST " + DeviceIngestionSecurityConfig.READINGS_URL);

    /** Operations that need no signed-in session ("METHOD path"). */
    private static final Set<String> PUBLIC_OPERATIONS = Set.of(
            "GET /api/auth/csrf",
            "POST " + SecurityConfig.LOGIN_URL);

    private static final String AUTH_TAG = "Authentication";

    @Bean
    public OpenAPI smartColdStorageOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Smart Cold Storage Monitor API")
                        .version("v1")
                        .description("""
                                Browser API of the Smart Cold Storage Monitor backend. All timestamps are UTC \
                                ISO 8601; errors use the shared `ApiError` body with a request id.

                                **Trying requests in Swagger UI** (same origin, so the session cookie is sent \
                                automatically):
                                1. `GET /api/auth/csrf`, copy `token`, click **Authorize** and paste it as `csrf`.
                                2. `POST /api/auth/login` with your username and password.
                                3. The CSRF token changes on login: call `GET /api/auth/csrf` again and update \
                                **Authorize** before any other POST/PATCH/DELETE."""))
                .components(new Components()
                        .addSecuritySchemes(SESSION_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("JSESSIONID")
                                .description("Server-side session cookie set by a successful login. "
                                        + "The browser sends it automatically; nothing to enter here."))
                        .addSecuritySchemes(CSRF_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-CSRF-TOKEN")
                                .description("Token from GET /api/auth/csrf. Required on every non-GET "
                                        + "request, including login and logout; fetch it again after login."))
                        .addSecuritySchemes(DEVICE_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("Authorization")
                                .description("Device credential for POST /api/readings only: "
                                        + "`Device <deviceId>:<token>`, where the token is the hex HMAC-SHA256 of "
                                        + "the device id under APP_DEVICE_SECRET. No session cookie or CSRF token.")));
    }

    @Bean
    public OpenApiCustomizer securityEndpointsCustomizer() {
        return openApi -> {
            registerSchemas(openApi, CurrentUserResponse.class, ApiError.class);
            Paths paths = openApi.getPaths() == null ? new Paths() : openApi.getPaths();
            openApi.setPaths(paths);
            paths.addPathItem(SecurityConfig.LOGIN_URL, new PathItem().post(loginOperation()));
            paths.addPathItem(SecurityConfig.LOGOUT_URL, new PathItem().post(logoutOperation()));
            paths.forEach((path, item) -> item.readOperationsMap().forEach(
                    (method, operation) -> operation.setSecurity(List.of(requirementFor(method, path)))));
        };
    }

    private static SecurityRequirement requirementFor(PathItem.HttpMethod method, String path) {
        SecurityRequirement requirement = new SecurityRequirement();
        if (DEVICE_OPERATIONS.contains(method + " " + path)) {
            return requirement.addList(DEVICE_SCHEME);
        }
        if (!PUBLIC_OPERATIONS.contains(method + " " + path)) {
            requirement.addList(SESSION_SCHEME);
        }
        if (method != PathItem.HttpMethod.GET) {
            requirement.addList(CSRF_SCHEME);
        }
        return requirement;
    }

    private static Operation loginOperation() {
        Schema<Object> form = new ObjectSchema()
                .addProperty("username", new StringSchema())
                .addProperty("password", new StringSchema().format("password"));
        form.setRequired(List.of("username", "password"));
        return new Operation()
                .operationId("login")
                .tags(List.of(AUTH_TAG))
                .summary("Sign in")
                .description("Starts a server-side session (the session id is rotated) and issues a new CSRF "
                        + "token. Every attempt is audited; the password is never stored or logged.")
                .requestBody(new RequestBody().required(true).content(new Content()
                        .addMediaType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                                new MediaType().schema(form))))
                .responses(new ApiResponses()
                        .addApiResponse("200", json("Signed in", CurrentUserResponse.class))
                        .addApiResponse("401", json("INVALID_CREDENTIALS: wrong username or password, or the "
                                + "account is disabled", ApiError.class))
                        .addApiResponse("403", json("Missing or invalid CSRF token", ApiError.class)));
    }

    private static Operation logoutOperation() {
        return new Operation()
                .operationId("logout")
                .tags(List.of(AUTH_TAG))
                .summary("Sign out")
                .description("Invalidates the current session and clears the session cookie.")
                .responses(new ApiResponses()
                        .addApiResponse("204", new ApiResponse().description("Signed out"))
                        .addApiResponse("403", json("Missing or invalid CSRF token", ApiError.class)));
    }

    private static ApiResponse json(String description, Class<?> body) {
        return new ApiResponse().description(description).content(new Content().addMediaType(
                org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + body.getSimpleName()))));
    }

    @SuppressWarnings("rawtypes")
    private static void registerSchemas(OpenAPI openApi, Class<?>... types) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        for (Class<?> type : types) {
            Map<String, Schema> schemas = ModelConverters.getInstance().readAll(type);
            schemas.forEach((name, schema) -> {
                if (openApi.getComponents().getSchemas() == null
                        || !openApi.getComponents().getSchemas().containsKey(name)) {
                    openApi.getComponents().addSchemas(name, schema);
                }
            });
        }
    }
}
