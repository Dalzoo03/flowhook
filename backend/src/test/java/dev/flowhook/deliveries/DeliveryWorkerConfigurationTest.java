package dev.flowhook.deliveries;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryWorkerConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DeliveryWorkerConfiguration.class);

    @Test void disabledOutsideLocalEvenWhenBothFlagsAreTrue() {
        runner.withPropertyValues("flowhook.delivery.worker-enabled=true", "flowhook.delivery.local-receiver-enabled=true")
                .run(context -> assertTrue(context.getBeansOfType(DeliveryWorker.class).isEmpty()));
    }

    @Test void disabledWithoutWorkerFlag() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues("flowhook.delivery.local-receiver-enabled=true")
                .run(context -> assertTrue(context.getBeansOfType(DeliveryWorker.class).isEmpty()));
    }

    @Test void disabledWhenLocalReceiverIsDisabled() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .withPropertyValues("flowhook.delivery.worker-enabled=true", "flowhook.delivery.local-receiver-enabled=false")
                .run(context -> assertTrue(context.getBeansOfType(DeliveryWorker.class).isEmpty()));
    }
}
