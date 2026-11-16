package dev.flowhook.deliveries;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(prefix = "flowhook.delivery",
        name = {"worker-enabled", "local-receiver-enabled"}, havingValue = "true")
@EnableScheduling
public class DeliveryWorkerConfiguration {
    @Bean
    ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        // 느린 전송 배치가 만료 작업의 회수를 막지 않게 별도 실행 여유를 둔다.
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("flowhook-worker-");
        return scheduler;
    }

    @Bean
    DeliveryWorker deliveryWorker(DeliveryTransactions transactions, DeliveryService service, LocalReceiverPolicy policy) {
        return new DeliveryWorker(transactions, service, policy);
    }
}
