package com.alejandro.mtostock;

import com.alejandro.mtostock.application.service.AssemblyService;
import com.alejandro.mtostock.application.service.MasterDataEventHandler;
import com.alejandro.mtostock.application.service.BOMCalculationService;
import com.alejandro.mtostock.application.service.MaterialService;
import com.alejandro.mtostock.application.service.ProjectService;
import com.alejandro.mtostock.application.service.ReservationService;
import com.alejandro.mtostock.application.service.StockCalculationService;
import com.alejandro.mtostock.application.service.StockMovementService;
import com.alejandro.mtostock.application.service.SupplierService;
import com.alejandro.mtostock.application.service.TransferService;
import com.alejandro.mtostock.application.service.WarehouseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import io.micrometer.tracing.Tracer;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "spring.data.jpa.auditing.enabled=false",
        // El contexto se monta con el perfil por defecto (dev), donde el canal de RabbitMQ esta
        // activo. Aqui se apaga porque no hay broker: el cableado del canal se comprueba en
        // MessagingLayerTest, que no necesita ninguno.
        "app.rabbitmq.enabled=false",
        // La cache ya viene apagada por defecto, pero va explicito por el mismo motivo que la linea
        // de arriba: deja escrito que este contexto no necesita Redis, y no se rompe el dia que
        // alguien la encienda por defecto.
        "app.cache.enabled=false"
})
class MtoStockApplicationTests {

    @MockitoBean
    private AssemblyService assemblyService;

    @MockitoBean
    private BOMCalculationService bomCalculationService;

    @MockitoBean
    private MaterialService materialService;

    @MockitoBean
    private ProjectService projectService;

    @MockitoBean
    private ReservationService reservationService;

    @MockitoBean
    private StockCalculationService stockCalculationService;

    @MockitoBean
    private StockMovementService stockMovementService;

    @MockitoBean
    private SupplierService supplierService;

    @MockitoBean
    private TransferService transferService;

    @MockitoBean
    private WarehouseService warehouseService;

    /**
     * El despachador de datos maestros recibe por constructor la lista de manejadores por entidad, y
     * hoy no hay ninguno. Se comprueba que aun asi se crea: si Spring tratara la coleccion vacia
     * como una dependencia sin satisfacer, la aplicacion no arrancaria hasta que alguien escribiera
     * el primer manejador, que es justo lo contrario de lo que se busca.
     */
    @Autowired(required = false)
    private MasterDataEventHandler masterDataEventHandler;

    /**
     * El puente de trazado. No lo trae Actuator por si solo: depende de que
     * {@code spring-boot-starter-opentelemetry} este en el classpath, y quitar esa dependencia no
     * rompe ninguna compilacion. Sin ella este servicio vuelve a ser el eslabon que corta la traza
     * que nace en mto-gateway, y no se enteraria nadie hasta buscar una traza y verla a medias.
     *
     * <p>No hace falta {@code @AutoConfigureTracing}: lo que Boot desactiva en los tests es la
     * EXPORTACION de spans ({@code spring.test.tracing.export}), no el trazado, asi que el Tracer
     * esta en el contexto igual y no se manda nada a un colector que no existe.</p>
     */
    @Autowired(required = false)
    private Tracer tracer;

    @Test
    void contextLoads() {
        assertNotNull(masterDataEventHandler);
    }

    @Test
    void elPuenteDeTrazadoEstaEnElContexto() {
        assertNotNull(tracer);
    }

}
