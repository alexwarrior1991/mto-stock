package com.alejandro.mtostock.configuration.scheduling;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.boot.thread.Threading;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * El planificador de los {@code @Scheduled}: un pool de {@code spring.task.scheduling.pool.size} (4)
 * hilos, virtuales con {@code spring.threads.virtual.enabled}, para que ninguna pasada espere a otra.
 *
 * <p>El relay del outbox, sus metricas y su purga son {@code fixedDelay}, y la purga de claves de
 * idempotencia, un cron. Con los hilos virtuales, Boot planifica con un
 * {@code SimpleAsyncTaskScheduler}, que corre todas las tareas {@code fixedDelay} en su unico hilo de
 * planificacion: una purga del outbox larga, o un relay esperando a la base o al broker, paraban a las
 * otras dos. Sin ellos, Boot usa un {@code ThreadPoolTaskScheduler} de un hilo si nadie fija el tamano,
 * y entonces tambien el cron espera. Aqui el pool es el mismo en los dos casos: cada tarea sigue sin
 * solaparse consigo misma (el siguiente {@code fixedDelay} se cuenta desde que termina la pasada
 * anterior), pero las demas no la esperan.</p>
 *
 * <p>No tiene que ver con {@code outbox-dispatch}, el hilo del despacho inmediato tras el commit, que
 * sigue siendo uno solo de plataforma a proposito ({@code OutboxConfiguration}): no es un
 * {@code @Scheduled} y no pasa por aqui.</p>
 *
 * <p>El planificador no es un bean a proposito: un {@code ThreadPoolTaskScheduler} es tambien un
 * {@code Executor}, y un {@code Executor} en el contexto haria que Boot dejara de crear su
 * {@code applicationTaskExecutor}. Boot sigue creando su {@code taskScheduler}, en el que ya no se
 * planifica nada. Este se cierra con el contexto.</p>
 */
@Configuration(proxyBeanMethods = false)
public class SchedulingConfiguration implements SchedulingConfigurer, DisposableBean {

    private final ThreadPoolTaskScheduler scheduler;

    public SchedulingConfiguration(ThreadPoolTaskSchedulerBuilder builder, Environment environment) {
        ThreadPoolTaskScheduler built = builder.build();
        if (Threading.VIRTUAL.isActive(environment)) {
            built.setThreadFactory(Thread.ofVirtual().name(built.getThreadNamePrefix(), 0).factory());
        }
        built.initialize();
        this.scheduler = built;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(scheduler);
    }

    @Override
    public void destroy() {
        scheduler.destroy();
    }
}
