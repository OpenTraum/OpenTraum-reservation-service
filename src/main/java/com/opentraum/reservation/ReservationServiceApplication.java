package com.opentraum.reservation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ReservationServiceApplication {

    public static void main(String[] args) {
        // Reactor Context <-> ThreadLocal 자동 전파. Micrometer Tracing(traceId/spanId)이
        // Mono/Flux 체인 전반에서 유지되고 MDC로도 흘러가도록 반드시 main 초기에 활성화해야 한다.
        reactor.core.publisher.Hooks.enableAutomaticContextPropagation();
        SpringApplication.run(ReservationServiceApplication.class, args);
    }
}
