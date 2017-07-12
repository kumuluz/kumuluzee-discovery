/*
 *  Copyright (c) 2014-2017 Kumuluz and/or its affiliates
 *  and other contributors as indicated by the @author tags and
 *  the contributor list.
 *
 *  Licensed under the MIT License (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  https://opensource.org/licenses/MIT
 *
 *  The software is provided "AS IS", WITHOUT WARRANTY OF ANY KIND, express or
 *  implied, including but not limited to the warranties of merchantability,
 *  fitness for a particular purpose and noninfringement. in no event shall the
 *  authors or copyright holders be liable for any claim, damages or other
 *  liability, whether in an action of contract, tort or otherwise, arising from,
 *  out of or in connection with the software or the use or other dealings in the
 *  software. See the License for the specific language governing permissions and
 *  limitations under the License.
*/
package com.kumuluz.ee.discovery.utils;

import com.kumuluz.ee.configuration.utils.ConfigurationUtil;
import com.kumuluz.ee.discovery.annotations.RegisterService;

import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.Initialized;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import javax.ws.rs.core.Application;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.logging.Logger;

/**
 * Interceptor class for RegisterService annotation.
 */
@ApplicationScoped
@WebListener
public class RegisterServiceUtil implements ServletContextListener {

    private static final Logger log = Logger.getLogger(RegisterServiceUtil.class.getName());

    private boolean beanInitialised;
    private boolean deregistratorEnabled;

    @Inject
    private DiscoveryUtil discoveryUtil;

    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent) {

        if (!beanInitialised) {
            beanInitialised = initialiseBean();
        }

    }

    public void cdiInitialized(@Observes @Initialized(ApplicationScoped.class) Object init) {

        if (!beanInitialised) {
            beanInitialised = initialiseBean();
        }

    }

    private boolean initialiseBean() {

        List<Application> applications = new ArrayList<>();

        ServiceLoader.load(Application.class).forEach(applications::add);

        for (Application application : applications) {
            log.info("Registering JAX-RS application class: " + application.getClass().getSimpleName());
            registerService(application.getClass());
        }

        return true;
    }

    /**
     * Method initialises class fields from configuration.
     */
    private void registerService(Class targetClass) {

        ConfigurationUtil configurationUtil = ConfigurationUtil.getInstance();

        if (targetClassIsProxied(targetClass)) {
            targetClass = targetClass.getSuperclass();
        }

        String serviceName = configurationUtil.get("kumuluzee.service-name").orElse(null);
        if(serviceName == null || serviceName.isEmpty()) {
            serviceName = ((RegisterService) targetClass.getAnnotation(RegisterService.class)).value();

            if (serviceName.isEmpty()) {
                serviceName = targetClass.getName();
            }
        }

        long ttl = configurationUtil.getInteger("kumuluzee.discovery.ttl").orElse(-1);
        if (ttl == -1) {
            ttl = ((RegisterService) targetClass.getAnnotation(RegisterService.class)).ttl();

            if(ttl == -1) {
                ttl = 30;
            }
        }

        long pingInterval = configurationUtil.getInteger("kumuluzee.discovery.ping-interval").orElse(-1);
        if (pingInterval == -1) {
            pingInterval = ((RegisterService) targetClass.getAnnotation(RegisterService.class)).pingInterval();

            if (pingInterval == -1) {
                pingInterval = 20;
            }
        }

        String environment = configurationUtil.get("kumuluzee.env").orElse(null);
        if (environment == null || environment.isEmpty()) {
            environment = ((RegisterService) targetClass.getAnnotation(RegisterService.class)).environment();

            if(environment.isEmpty()) {
                environment = "dev";
            }
        }

        String version = configurationUtil.get("kumuluzee.version").orElse(null);
        if (version == null || version.isEmpty()) {
            version = ((RegisterService) targetClass.getAnnotation(RegisterService.class)).version();

            if(version.isEmpty()) {
                version = "1.0.0";
            }
        }

        boolean singleton = ((RegisterService) targetClass.getAnnotation(RegisterService.class)).singleton();

        log.info("Registering service: " + serviceName);

        discoveryUtil.register(serviceName, version, environment, ttl, pingInterval, singleton);

        // enable service deregistrator
        this.deregistratorEnabled = true;
    }

    /**
     * Check if target class is proxied.
     *
     * @param targetClass target class
     * @return true if target class is proxied
     */
    private boolean targetClassIsProxied(Class targetClass) {
        return targetClass.getCanonicalName().contains("$Proxy");
    }


    @PreDestroy
    public void deregisterService() {

        if (deregistratorEnabled) {
            discoveryUtil.deregister();
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent servletContextEvent) {

    }
}
