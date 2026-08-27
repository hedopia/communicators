package com.sds.communicators.io;

import com.sds.communicators.driver.DriverStarter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.reactor.netty.NettyRouteProvider;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IoApplication {
    public static void main(String[] args) {
        SpringApplication.run(IoApplication.class, args);
    }

    /**
     * contributes the cluster/driver REST routes and the driver web UI to the Reactor Netty
     * server that Spring Boot owns. Spring Boot appends its own WebFlux handler as a catch-all
     * after every NettyRouteProvider, so WebFlux endpoints keep working alongside these routes.
     */
    @Bean
    public NettyRouteProvider driverRouteProvider(DriverStarter driverStarter) {
        return routes -> {
            driverStarter.getRoutes().accept(routes);
            return routes;
        };
    }
}
