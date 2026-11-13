package com.icu.smart_cold_storage_monitor.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icu.smart_cold_storage_monitor.support.SqliteIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The OpenAPI document and Swagger UI are served without signing in and describe the security filter endpoints. */
@AutoConfigureMockMvc
class OpenApiDocsTest extends SqliteIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void apiDocsArePublicAndIncludeControllerAndSecurityEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/users']").exists())
                .andExpect(jsonPath("$.paths['/api/audit-events'].get").exists())
                .andExpect(jsonPath("$.paths['/api/auth/me'].get").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.requestBody.content"
                        + "['application/x-www-form-urlencoded'].schema.properties.username").exists())
                .andExpect(jsonPath("$.paths['/api/auth/logout'].post.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/users'].post.responses['201'].content['application/json']"
                        + ".schema['$ref']").value("#/components/schemas/CredentialResponse"))
                .andExpect(jsonPath("$.paths['/api/users'].post.responses['200']").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.ApiError").exists())
                .andExpect(jsonPath("$.components.securitySchemes.csrf.name").value("X-CSRF-TOKEN"));
    }

    @Test
    void securityRequirementsMatchTheFilterChain() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                // Login needs a CSRF token but no session; the CSRF endpoint needs neither.
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.security[0].csrf").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.security[0].session").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/auth/csrf'].get.security[0].session").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/auth/csrf'].get.security[0].csrf").doesNotExist())
                // Signed-in GETs need only the session; mutations need both.
                .andExpect(jsonPath("$.paths['/api/auth/me'].get.security[0].session").exists())
                .andExpect(jsonPath("$.paths['/api/auth/me'].get.security[0].csrf").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/users'].post.security[0].session").exists())
                .andExpect(jsonPath("$.paths['/api/users'].post.security[0].csrf").exists())
                // Device ingestion uses only the device credential: no session, no CSRF.
                .andExpect(jsonPath("$.paths['/api/readings'].post.security[0].device").exists())
                .andExpect(jsonPath("$.paths['/api/readings'].post.security[0].session").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/readings'].post.security[0].csrf").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/readings'].post.parameters").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/readings'].post.responses['201']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.device.name").value("Authorization"))
                // The dashboard query is a signed-in GET: session only.
                .andExpect(jsonPath("$.paths['/api/readings/query'].get.security[0].session").exists())
                .andExpect(jsonPath("$.paths['/api/readings/query'].get.security[0].csrf").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/readings/query'].get.parameters[?(@.name == 'deviceId')]").exists())
                .andExpect(jsonPath("$.paths['/api/readings/query'].get.parameters[?(@.name == 'metric')]").exists())
                // The CsrfToken and principal method arguments are framework-injected, not client parameters.
                .andExpect(jsonPath("$.paths['/api/auth/csrf'].get.parameters").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/auth/me'].get.parameters").doesNotExist());
    }

    @Test
    void swaggerUiIsServedWithoutSigningIn() throws Exception {
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("swagger-ui")));
    }
}
