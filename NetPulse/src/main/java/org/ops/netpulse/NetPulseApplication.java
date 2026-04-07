package org.ops.netpulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling
@SpringBootApplication(exclude = {
    RabbitAutoConfiguration.class,
})
public class NetPulseApplication {

    public static void main(String[] args) {
        SpringApplication.run(NetPulseApplication.class, args);
    }
}
