// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
package eu.socle.config;

import eu.socle.audit.AuthMethodContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig implements AsyncConfigurer {

    /**
     * Même exécuteur que le repli {@code @Async} de Spring (les exécuteurs du broker WebSocket
     * font reculer celui de Spring Boot), décoré pour propager la méthode d'authentification
     * (jeton d'accès personnel) aux écritures d'audit asynchrones.
     */
    @Override
    public Executor getAsyncExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor();
        executor.setTaskDecorator(AuthMethodContext.taskDecorator());
        return executor;
    }
}
