package com.alejandro.mtostock.configuration.inbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Cuanto se recuerda un mensaje del inbox ({@code app.inbox.*}).
 *
 * <p>La fila de un mensaje aplicado solo hace falta mientras pueda volver a llegar con el mismo
 * identificador: una reentrega del broker (segundos; a la quinta, o al primer rechazo en una cola
 * clasica, va a la DLQ), un reintento del outbox del emisor (algo mas de una hora con sus 20 intentos) o
 * un mensaje de la DLQ o un {@code FAILED} del outbox del emisor que alguien reencola a mano, que es lo
 * unico que puede tardar dias. Un republicado de datos maestros no cuenta: lleva identificador nuevo.
 * Treinta dias sobran para todo eso, y son los que este servicio recuerda una clave de idempotencia.
 * Pasado el plazo, un duplicado se aplicaria otra vez, y ahi lo para la marca de agua de la secuencia
 * ({@code project.source_sequence_number}) dentro del upsert y de la desactivacion del proyecto, lo unico
 * que un evento escribe aqui: no depende de esta tabla.</p>
 *
 * <p>Las fallidas se guardan mas: no protegen de nada (una fallida se vuelve a reclamar al reencolarla, y
 * si ya no esta se inserta de nuevo), pero su fila dice por que acabo un mensaje en la DLQ.</p>
 *
 * <p>El interruptor de la purga ({@code app.inbox.purge.enabled}) y su horario
 * ({@code app.inbox.purge.cron}) no estan aqui: los leen {@code @ConditionalOnProperty} y
 * {@code @Scheduled} en {@link InboxPurgeConfiguration}. Un plazo vacio, cero o negativo se sustituye por
 * el de por defecto: olvidar un mensaje en el acto dejaria al inbox sin la idempotencia que promete.</p>
 *
 * @param retention       lo que se recuerda un mensaje aplicado, desde que se aplico (30 dias)
 * @param failedRetention lo que se guarda uno fallido, desde su ultimo fallo (90 dias)
 * @param purge           como se borra
 */
@ConfigurationProperties(prefix = "app.inbox")
public record InboxProperties(Duration retention, Duration failedRetention, Purge purge) {

    public static final Duration DEFAULT_RETENTION = Duration.ofDays(30);
    public static final Duration DEFAULT_FAILED_RETENTION = Duration.ofDays(90);
    public static final int DEFAULT_BATCH_SIZE = 500;

    public InboxProperties {
        retention = positive(retention) ? retention : DEFAULT_RETENTION;
        failedRetention = positive(failedRetention) ? failedRetention : DEFAULT_FAILED_RETENTION;
        purge = purge == null ? new Purge(null) : purge;
    }

    private static boolean positive(Duration duration) {
        return duration != null && !duration.isNegative() && !duration.isZero();
    }

    /**
     * @param batchSize filas por sentencia (500): un {@code DELETE} de millones de filas hincha el WAL y
     *                  retiene sus bloqueos hasta el final
     */
    public record Purge(Integer batchSize) {

        public Purge {
            batchSize = batchSize == null || batchSize <= 0 ? DEFAULT_BATCH_SIZE : batchSize;
        }
    }
}
