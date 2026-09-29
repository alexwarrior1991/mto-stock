package com.alejandro.mtostock;

import com.alejandro.mtostock.application.dto.messaging.MasterDataEntityNames;
import com.alejandro.mtostock.application.service.AssemblyService;
import com.alejandro.mtostock.application.service.BOMCalculationService;
import com.alejandro.mtostock.application.service.DomainEventPublisher;
import com.alejandro.mtostock.application.dto.messaging.DomainEvent;
import com.alejandro.mtostock.infrastructure.messaging.outbox.OutboxMessageRepository;
import com.alejandro.mtostock.application.service.EntityAuditService;
import com.alejandro.mtostock.application.service.IdempotentRequestService;
import com.alejandro.mtostock.application.service.InboxMessageService;
import com.alejandro.mtostock.application.service.InventoryBalanceService;
import com.alejandro.mtostock.application.service.InventoryValidationService;
import com.alejandro.mtostock.application.service.MasterDataEntityHandler;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * <p>De ahí que este test necesite un PostgreSQL de verdad (Docker, o {@code TEST_DATABASE_URL}; ver
 * {@link PostgreSQLTestContainer}): sin datasource no hay repositorios, y sin repositorios no se
 * puede comprobar que los servicios que dependen de ellos existan.</p>
 */
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
     * Los manejadores por entidad, que es la lista que recibe el despachador.
     */
    @Autowired(required = false)
    private List<MasterDataEntityHandler> masterDataEntityHandlers;

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

    @Autowired
    private DomainEventPublisher domainEventPublisher;

    @Autowired
    private OutboxMessageRepository outboxMessageRepository;

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
                // El publicador de eventos propios: con el broker apagado es el NoOp, pero tiene que existir
                // porque saldo, reservas y ajustes lo piden por constructor.
                DomainEventPublisher.class,
                // Lo piden por constructor los seis impls que sirven /revisions. Faltaba de esta
                // lista desde que llegó con Envers, que es justo cuando un guardián de arranque
                // deja de servir: el hueco lo abre siempre el servicio recién añadido.
                EntityAuditService.class,
                IdempotentRequestService.class,
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

    /**
     * El manejador del paquete de ejecución es el único que sincroniza datos maestros, y se
     * comprueba aparte de los servicios porque falla de otra manera: si dejara de ser bean la
     * aplicación arrancaría igual. El despachador ignora a propósito las entidades que nadie
     * atiende —la cola está enlazada a {@code mto.master-data.#} y llega todo—, así que sus eventos
     * pasarían a marcarse como aplicados sin hacer nada y los proyectos dejarían de sincronizarse en
     * silencio, sin un error en ninguna parte.
     */
    @Test
    void elManejadorDelPaqueteDeEjecucionEstaRegistrado() {
        assertThat(masterDataEntityHandlers)
                .withFailMessage("No hay ningun MasterDataEntityHandler en el contexto")
                .isNotNull();
        assertThat(masterDataEntityHandlers)
                .extracting(MasterDataEntityHandler::entityName)
                .withFailMessage("""
                        Ningun MasterDataEntityHandler atiende '%s'. La aplicacion arranca igual y \
                        el despachador tratara sus eventos como entidad desconocida: se marcaran \
                        como aplicados sin sincronizar ningun proyecto y no fallara nada.""",
                        MasterDataEntityNames.EXECUTION_PACKAGE)
                .contains(MasterDataEntityNames.EXECUTION_PACKAGE);
    }

    @Test
    void elPuenteDeTrazadoEstaEnElContexto() {
        assertNotNull(tracer);
    }

    /**
     * Con el broker apagado el publicador de eventos es el NoOp y no queda ninguna pieza del outbox,
     * pero la tabla si existe (V10) y la entidad valida contra ella. Se compara antes y despues porque
     * la base es la misma que usa OutboxRelayDataJpaTest, que deja filas.
     */
    @Test
    void conElBrokerApagadoLosEventosNoSePublicanYElOutboxNoSeToca() {
        assertFalse(domainEventPublisher.isEnabled());
        assertFalse(context.containsBean("outboxRabbitPublisher"));
        assertFalse(context.containsBean("outboxPublisherScheduler"));
        assertFalse(context.containsBean("outboxEndpoint"));

        long before = outboxMessageRepository.count();
        domainEventPublisher.publish(new DomainEvent("material", "b0000000-0000-4000-8000-000000000001", "below-minimum", java.util.Map.of("materialCode", "GA70")));

        assertEquals(before, outboxMessageRepository.count(), "el evento paso por el NoOp: no se escribio nada");
    }

}
