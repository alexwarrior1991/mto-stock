package com.alejandro.mtostock.infrastructure.persistence.audit;

/**
 * Lleva el identificador del mensaje que se está procesando hasta el listener de revisiones.
 *
 * <p>Una escritura que viene de RabbitMQ corre fuera del ciclo de una petición HTTP: no hay IP, ni
 * User-Agent, ni URI, ni cabecera de correlación. Sin esto, la revisión quedaría como una escritura
 * de {@code system} sin nada que la ate al mensaje que la provocó, y la pregunta «¿qué evento
 * cambió este proyecto?» se contestaría cruzando marcas de tiempo a ojo.</p>
 *
 * <p>Se usa un {@code ThreadLocal} propio y no el MDC de SLF4J a propósito: el MDC es un canal de
 * logging compartido con el puente de trazas, es un {@code Map<String,String>} sin contrato, y haría
 * que la auditoría dependiera de que alguien se acordara de rellenar una clave de texto.</p>
 *
 * <p><b>Hay que limpiarlo siempre</b>, en un {@code finally}: los hilos del listener de RabbitMQ
 * salen de un pool y se reutilizan, así que un contexto que se quede colgado atribuiría la revisión
 * de un mensaje posterior al identificador de este.</p>
 *
 * <p>Hoy este camino no produce ninguna revisión, porque el único {@code MasterDataEntityHandler}
 * que existe escribe {@code project} con SQL nativo y Envers no lo ve (ver {@code docs/07-auditing.md}).
 * El sitio está preparado para el primer handler que escriba a través de una entidad.</p>
 */
public final class MessagingAuditContext {

    /**
     * @param messageId     la clave de idempotencia del inbox: el {@code operationId} del sobre, con
     *                      el {@code message_id} de AMQP como respaldo. Es la misma con la que se
     *                      puede cruzar la revisión contra su fila de {@code inbox_message}.
     * @param sourceService el servicio que publicó el evento.
     */
    public record Context(String messageId, String sourceService) {
    }

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private MessagingAuditContext() {
    }

    public static void set(Context context) {
        CURRENT.set(context);
    }

    /** @return el contexto del mensaje en curso, o {@code null} si la escritura no viene de uno. */
    public static Context current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
