package com.alejandro.mtostock;

import com.alejandro.mtostock.application.service.AssemblyService;
import com.alejandro.mtostock.application.service.BOMCalculationService;
import com.alejandro.mtostock.application.service.InboxMessageService;
import com.alejandro.mtostock.application.service.InventoryBalanceService;
import com.alejandro.mtostock.application.service.InventoryValidationService;
import com.alejandro.mtostock.application.service.MasterDataEventHandler;
import com.alejandro.mtostock.application.service.MasterDataEventProcessor;
import com.alejandro.mtostock.application.service.MaterialService;
import com.alejandro.mtostock.application.service.ProjectService;
import com.alejandro.mtostock.application.service.ReservationEngine;
import com.alejandro.mtostock.application.service.ReservationService;
import com.alejandro.mtostock.application.service.StockCalculationService;
import com.alejandro.mtostock.application.service.StockMovementService;
import com.alejandro.mtostock.application.service.SupplierService;
import com.alejandro.mtostock.application.service.TransferService;
import com.alejandro.mtostock.application.service.WarehouseService;
import com.alejandro.mtostock.support.PostgreSQLTestContainer;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Monta el contexto ENTERO, con base de datos de verdad y sin sustituir ningún servicio por un
 * mock. Es lo único que comprueba que la aplicación arranca tal y como se empaqueta.
 *
 * <p>Antes mockeaba los diez servicios con {@code @MockitoBean} y excluía
 * {@code DataSourceAutoConfiguration}. Con eso los controladores recibían los mocks y los impls
 * reales no llegaban a instanciarse nunca, así que el test pasaba mientras la aplicación de verdad
 * no arrancaba: los 16 impls llevaban {@code @ConditionalOnBean(XRepository.class)}, una anotación
 * que Spring solo admite en autoconfiguraciones —en un {@code @Service} escaneado se evalúa antes
 * de que Spring Data registre los repositorios, así que siempre era falsa— y ningún servicio se
 * creaba. Lo destapó el smoke test de la imagen en CI, no la suite.</p>
 *
 * <p>De ahí que este test necesite Docker: sin datasource no hay repositorios, y sin repositorios
 * no se puede comprobar que los servicios que dependen de ellos existan.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        // No hay broker ni Redis en este test. El cableado del canal se comprueba en
        // MessagingLayerTest y el de la caché en CacheLayerTest, ninguno de los dos los necesita.
        "app.rabbitmq.enabled=false",
        "app.cache.enabled=false"
})
class MtoStockApplicationTests extends PostgreSQLTestContainer {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registerPostgreSQLProperties(registry);
    }

    @Autowired
    private ApplicationContext context;

    /**
     * El despachador de datos maestros recibe por constructor la lista de manejadores por entidad, y
     * hoy solo hay uno. Se comprueba que se crea: si Spring tratara la colección como una
     * dependencia sin satisfacer, la aplicación no arrancaría hasta que alguien escribiera el
     * primer manejador, que es justo lo contrario de lo que se busca.
     */
    @Autowired(required = false)
    private MasterDataEventHandler masterDataEventHandler;

    /**
     * El puente de trazado. No lo trae Actuator por sí solo: depende de que
     * {@code spring-boot-starter-opentelemetry} esté en el classpath, y quitar esa dependencia no
     * rompe ninguna compilación. Sin ella este servicio vuelve a ser el eslabón que corta la traza
     * que nace en mto-gateway, y no se enteraría nadie hasta buscar una traza y verla a medias.
     *
     * <p>No hace falta {@code @AutoConfigureTracing}: lo que Boot desactiva en los tests es la
     * EXPORTACIÓN de spans ({@code spring.test.tracing.export}), no el trazado, así que el Tracer
     * está en el contexto igual y no se manda nada a un colector que no existe.</p>
     */
    @Autowired(required = false)
    private Tracer tracer;

    @Test
    void contextLoads() {
        assertNotNull(masterDataEventHandler);
    }

    /**
     * Cada servicio de negocio tiene que estar en el contexto de verdad, no en forma de mock. Es la
     * comprobación que faltaba: con los controladores pidiéndolos por constructor, que falte uno
     * significa que la aplicación no arranca, y hasta ahora eso solo se veía al ejecutar la imagen.
     */
    @Test
    void todosLosServiciosDeNegocioEstanEnElContexto() {
        List<Class<?>> servicios = List.of(
                AssemblyService.class,
                BOMCalculationService.class,
                InboxMessageService.class,
                InventoryBalanceService.class,
                InventoryValidationService.class,
                MasterDataEventProcessor.class,
                MaterialService.class,
                ProjectService.class,
                ReservationEngine.class,
                ReservationService.class,
                StockCalculationService.class,
                StockMovementService.class,
                SupplierService.class,
                TransferService.class,
                WarehouseService.class);

        for (Class<?> servicio : servicios) {
            assertThat(context.getBeanNamesForType(servicio))
                    .withFailMessage("""
                            No hay ningun bean de %s en el contexto. Los controladores lo piden por \
                            constructor, asi que la aplicacion no arranca. Comprobar que su impl \
                            sigue anotado con @Service y que no ha vuelto un @ConditionalOnBean: \
                            esa anotacion solo vale en autoconfiguraciones y aqui es siempre falsa.""",
                            servicio.getSimpleName())
                    .isNotEmpty();
        }
    }

    @Test
    void elPuenteDeTrazadoEstaEnElContexto() {
        assertNotNull(tracer);
    }

}
