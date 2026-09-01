package com.alejandro.mtostock.infrastructure.messaging.rabbitmq;

/**
 * Broker object names of the master data channel, split by who owns each one.
 *
 * <p>El exchange y la routing key <b>no son decisiones de este servicio</b>: los fija
 * {@code mto-configuration} en su {@code MasterDataRabbitMqNames} y aquí solo se replican. Cambiar
 * cualquiera de los dos deja de recibir eventos sin ningún error visible, porque un binding contra
 * un exchange que nadie usa se declara igual de bien.</p>
 *
 * <p>La cola y su cola de errores sí son de este servicio. Una cola pertenece a quien la CONSUME,
 * que es quien sabe qué necesita, y por eso {@code mto-stock} declara la suya en lugar de pedirle
 * al emisor que la añada a su lista. Los sufijos {@code .dlx}/{@code .dlq} siguen la convención que
 * ya usa {@code mto-configuration} para que las dos consolas se lean igual.</p>
 *
 * <p>Los argumentos de una cola que ya existe son inmutables: añadir aquí un TTL o un límite a una
 * cola creada en un entorno hace que el broker responda {@code PRECONDITION_FAILED} y se caiga la
 * declaración entera, bindings incluidos. Para poner límites a una cola viva se usa una policy del
 * broker.</p>
 */
public final class MasterDataRabbitMqNames {

    /** Exchange de tipo topic que declara y usa {@code mto-configuration}. */
    public static final String MASTER_DATA_EXCHANGE = "mto.master-data.exchange";

    /**
     * Todos los cambios de datos maestros. Las routing keys reales son
     * {@code mto.master-data.<entidad>.<created|updated|deleted>}; se escucha el patrón completo
     * porque filtrar por entidad en el broker obligaría a redeclarar bindings cada vez que el
     * dominio de {@code mto-configuration} crece.
     */
    public static final String MASTER_DATA_ROUTING_PATTERN = "mto.master-data.#";

    /** Cola de este servicio. */
    public static final String STOCK_MASTER_DATA_QUEUE = "mto.stock.master-data.queue";

    /** Exchange al que el broker reenvía lo que esta cola rechaza. */
    public static final String STOCK_MASTER_DATA_DEAD_LETTER_EXCHANGE = STOCK_MASTER_DATA_QUEUE + ".dlx";

    /** Cola de errores: aquí acaba lo que no se pudo deserializar ni procesar. */
    public static final String STOCK_MASTER_DATA_DEAD_LETTER_QUEUE = STOCK_MASTER_DATA_QUEUE + ".dlq";

    /** Routing key con la que el mensaje rechazado llega a la DLQ. */
    public static final String STOCK_MASTER_DATA_DEAD_LETTER_ROUTING_KEY = STOCK_MASTER_DATA_QUEUE + ".dlq";

    /** Argumento de declaración: exchange de descarte de la cola. */
    public static final String ARG_DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";

    /** Argumento de declaración: routing key de descarte de la cola. */
    public static final String ARG_DEAD_LETTER_ROUTING_KEY = "x-dead-letter-routing-key";

    private MasterDataRabbitMqNames() {
    }
}
