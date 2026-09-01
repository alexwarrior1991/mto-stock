package com.alejandro.mtostock.configuration.security;

import com.alejandro.mtostock.infrastructure.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lo que se fija aquí es que cada verbo pida su permiso y, sobre todo, que los permisos no se
 * impliquen entre sí: poder escribir no debe bastar para borrar ni para ajustar el inventario.
 * <p>
 * Se prueba contra controladores sonda montados en rutas con la misma forma que las reales, no
 * contra los controladores de negocio: lo que se verifica son los patrones de la cadena de filtros,
 * y arrastrar la capa de servicio solo añadiría ruido y fragilidad.
 */
@WebMvcTest(controllers = ApiAuthorizationRulesTest.ProbeController.class)
@AutoConfigureMockMvc
@Import({SecurityConfiguration.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        // El slice recoge el advice de errores, que es quien traduce el 401 de la cadena y el 403
        // de @PreAuthorize al contrato de la API.
        GlobalExceptionHandler.class,
        ApiAuthorizationRulesTest.ProbeController.class,
        ApiAuthorizationRulesTest.ProbeAdjustmentController.class})
@TestPropertySource(properties = {
        "app.security.client-id=mto-stock-api",
        "app.security.principal-claim=preferred_username",
        "app.security.audience-validation-enabled=false",
        "app.security.expose-api-docs=false",
        "app.security.cors.allowed-origins=http://localhost:4200",
        "app.security.cors.allowed-methods=GET,POST,PUT,PATCH,DELETE",
        "app.security.cors.allowed-headers=Authorization,Content-Type",
        "app.security.cors.allow-credentials=false",
        "app.security.cors.max-age=3600",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8082/realms/mto"
})
class ApiAuthorizationRulesTest {

    private static final String PROBES = SecurityConfiguration.API + "/probes";
    private static final String ADJUSTMENTS = SecurityConfiguration.API + "/probe-movements/adjustments";

    /**
     * Se deja el {@code JwtDecoder} real, sin sustituir por un doble: así el contexto ejercita el
     * cableado del bean —que depende de {@code OAuth2ResourceServerProperties}— y no solo las
     * reglas. No toca la red porque el JWK Set se descarga de forma perezosa, y {@code jwt()}
     * inyecta la autenticación ya resuelta, de modo que nunca llega a decodificar nada.
     */
    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextPublishesTheDecoderBuiltByTheConfiguration() {
        assertNotNull(jwtDecoder);
    }

    @Nested
    class WithoutToken {

        @Test
        void readReturnsUnauthorizedWithTheApiErrorContract() throws Exception {
            mockMvc.perform(get(PROBES + "/1"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("AUTH-401"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.path").value(PROBES + "/1"));
        }

        @Test
        void writeReturnsUnauthorizedRatherThanForbidden() throws Exception {
            mockMvc.perform(post(PROBES)).andExpect(status().isUnauthorized());
            mockMvc.perform(delete(PROBES + "/1")).andExpect(status().isUnauthorized());
        }

        @Test
        void actuatorHealthAndInfoStayOpen() throws Exception {
            // 404 y no 401: la ruta está permitida, simplemente este slice no monta Actuator.
            mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
            mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isNotFound());
            mockMvc.perform(get("/actuator/info")).andExpect(status().isNotFound());
        }

        @Test
        void theRestOfActuatorStaysClosed() throws Exception {
            mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        }

        @Test
        void apiDocsStayClosedWhileExposeApiDocsIsFalse() throws Exception {
            mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    class WithReadRole {

        @Test
        void readSucceeds() throws Exception {
            mockMvc.perform(get(PROBES + "/1").with(role(SecurityRoles.STOCK_READ))).andExpect(status().isOk());
        }

        /**
         * HEAD lo sirve el mismo handler que GET y revela si un recurso existe, así que no puede
         * caer en el {@code anyRequest().authenticated()} del final de la cadena.
         */
        @Test
        void headIsGovernedByTheReadRuleAndNotByPlainAuthentication() throws Exception {
            mockMvc.perform(head(PROBES + "/1").with(role(SecurityRoles.STOCK_READ))).andExpect(status().isOk());
            mockMvc.perform(head(PROBES + "/1").with(role(SecurityRoles.OPS_METRICS))).andExpect(status().isForbidden());
        }

        @Test
        void readingDoesNotGrantWriting() throws Exception {
            mockMvc.perform(post(PROBES).with(role(SecurityRoles.STOCK_READ)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("AUTH-403"));
            mockMvc.perform(put(PROBES + "/1").with(role(SecurityRoles.STOCK_READ))).andExpect(status().isForbidden());
            mockMvc.perform(patch(PROBES + "/1").with(role(SecurityRoles.STOCK_READ))).andExpect(status().isForbidden());
            mockMvc.perform(delete(PROBES + "/1").with(role(SecurityRoles.STOCK_READ))).andExpect(status().isForbidden());
        }
    }

    @Nested
    class WithWriteRole {

        @Test
        void writingSucceeds() throws Exception {
            mockMvc.perform(post(PROBES).with(role(SecurityRoles.STOCK_WRITE))).andExpect(status().isOk());
            mockMvc.perform(put(PROBES + "/1").with(role(SecurityRoles.STOCK_WRITE))).andExpect(status().isOk());
            mockMvc.perform(patch(PROBES + "/1").with(role(SecurityRoles.STOCK_WRITE))).andExpect(status().isOk());
        }

        @Test
        void writingDoesNotGrantReadingOrDeleting() throws Exception {
            mockMvc.perform(get(PROBES + "/1").with(role(SecurityRoles.STOCK_WRITE))).andExpect(status().isForbidden());
            mockMvc.perform(delete(PROBES + "/1").with(role(SecurityRoles.STOCK_WRITE))).andExpect(status().isForbidden());
        }

        /**
         * El ajuste es la única escritura que corrige el saldo sin documento con el que
         * contrastarlo. Sin este permiso aparte, quien pudiera registrar una salida podría además
         * hacerla desaparecer del saldo.
         */
        @Test
        void writingDoesNotGrantAdjusting() throws Exception {
            mockMvc.perform(post(ADJUSTMENTS).with(role(SecurityRoles.STOCK_WRITE)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("AUTH-403"));
        }
    }

    @Nested
    class WithDeleteAndAdjustRoles {

        @Test
        void deletingSucceedsOnlyWithItsOwnRole() throws Exception {
            mockMvc.perform(delete(PROBES + "/1").with(role(SecurityRoles.STOCK_DELETE))).andExpect(status().isNoContent());
        }

        /**
         * El ajuste necesita las dos mitades: la ruta le da el permiso de escribir y la anotación el
         * de descuadrar.
         */
        @Test
        void adjustingNeedsBothTheWriteAndTheAdjustRole() throws Exception {
            mockMvc.perform(post(ADJUSTMENTS).with(role(SecurityRoles.STOCK_ADJUST))).andExpect(status().isForbidden());
            mockMvc.perform(post(ADJUSTMENTS).with(role(SecurityRoles.STOCK_WRITE, SecurityRoles.STOCK_ADJUST)))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    class WithOperationsRoles {

        @Test
        void metricsReaderCannotTouchTheBusinessApi() throws Exception {
            mockMvc.perform(get(PROBES + "/1").with(role(SecurityRoles.OPS_METRICS))).andExpect(status().isForbidden());
        }

        /**
         * Leer métricas es observar; republicar o reconfigurar algo cambia el estado del sistema.
         * Con un solo rol, cualquiera que pudiera consultar Prometheus podría además dispararlo.
         */
        @Test
        void readingActuatorDoesNotGrantWritingToActuator() throws Exception {
            mockMvc.perform(post("/actuator/loggers/root").with(role(SecurityRoles.OPS_METRICS))).andExpect(status().isForbidden());
            // 404 y no 403: el permiso pasa, el endpoint no existe en este slice.
            mockMvc.perform(post("/actuator/loggers/root").with(role(SecurityRoles.OPS_WRITE))).andExpect(status().isNotFound());
        }
    }

    private static RequestPostProcessor role(String... roles) {
        String[] authorities = new String[roles.length];
        for (int index = 0; index < roles.length; index++) {
            authorities[index] = SecurityAuthorityPrefixes.ROLE_PREFIX + roles[index];
        }
        return jwt().authorities(AuthorityUtils.createAuthorityList(authorities));
    }

    @RestController
    @RequestMapping(SecurityConfiguration.API + "/probes")
    static class ProbeController {

        @GetMapping("/{id}")
        String find(@PathVariable String id) {
            return id;
        }

        @PostMapping
        String create() {
            return "created";
        }

        @PutMapping("/{id}")
        String replace(@PathVariable String id) {
            return id;
        }

        @PatchMapping("/{id}")
        String update(@PathVariable String id) {
            return id;
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void cancel(@PathVariable String id) {
        }
    }

    /** Réplica de la guarda que lleva el endpoint real de ajustes en {@code StockMovementController}. */
    @RestController
    @RequestMapping(SecurityConfiguration.API + "/probe-movements")
    static class ProbeAdjustmentController {

        @PreAuthorize("hasRole('" + SecurityRoles.STOCK_ADJUST + "')")
        @PostMapping("/adjustments")
        String adjust() {
            return "adjusted";
        }
    }
}
