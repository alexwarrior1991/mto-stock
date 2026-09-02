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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "spring.data.jpa.auditing.enabled=false",
        // El contexto se monta con el perfil por defecto (dev), donde el canal de RabbitMQ esta
        // activo. Aqui se apaga porque no hay broker: el cableado del canal se comprueba en
        // MessagingLayerTest, que no necesita ninguno.
        "app.rabbitmq.enabled=false"
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

    @Test
    void contextLoads() {
        assertNotNull(masterDataEventHandler);
    }

}
