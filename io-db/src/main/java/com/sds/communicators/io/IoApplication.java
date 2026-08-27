package com.sds.communicators.io;

import com.sds.communicators.driver.DriverStarter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.reactor.netty.NettyRouteProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.ReactorResourceFactory;
import reactor.netty.resources.LoopResources;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IoApplication {
    /** matches the thread count ClusterStarter.start() used before Spring Boot owned the server */
    private static final int HTTP_SERVER_THREAD_POOL_SIZE = 200;

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

    /**
     * replaces the auto-configured factory to keep the large worker pool the driver routes need:
     * they block on cluster redirects and device connect/disconnect, which would starve the
     * event-loop-sized default. Declared here rather than in IoConfiguration because only a
     * component-scanned @Configuration is registered early enough for the auto-configuration's
     * @ConditionalOnMissingBean to back off.
     */
    @Bean
    public ReactorResourceFactory reactorResourceFactory() {
        var resourceFactory = new ReactorResourceFactory();
        resourceFactory.setUseGlobalResources(false);
        resourceFactory.setLoopResources(LoopResources.create("http", HTTP_SERVER_THREAD_POOL_SIZE, true));
        return resourceFactory;
    }
}
