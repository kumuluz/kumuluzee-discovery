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

import com.kumuluz.ee.common.config.EeConfig;
import com.kumuluz.ee.configuration.utils.ConfigurationUtil;
import com.kumuluz.ee.discovery.annotations.DiscoverService;
import com.kumuluz.ee.discovery.enums.AccessType;
import com.kumuluz.ee.discovery.exceptions.ServiceNotFoundException;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.inject.Inject;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Producer for DiscoverService annotation.
 *
 * @author Jan Meznaric
 * @since 1.0.0
 */
@ApplicationScoped
public class DiscoverServiceProducer {

    private static final Logger log = Logger.getLogger(DiscoverServiceProducer.class.getName());

    private static final String SERVICE_NOT_FOUNT_MSG = "Service not found.";

    @Inject
    private DiscoveryUtil discoveryUtil;

    @Produces
    @DiscoverService
    public Optional<URL> produceUrlOpt(InjectionPoint injectionPoint) {

        return getUrl(injectionPoint);

    }

    @Produces
    @DiscoverService
    public Optional<String> produceStringOpt(InjectionPoint injectionPoint) {

        Optional<URL> url = getUrl(injectionPoint);

        return url.map(URL::toString);
    }

    @Produces
    @DiscoverService
    public Optional<WebTarget> produceWebTargetOpt(InjectionPoint injectionPoint) {

        Optional<URL> url = getUrl(injectionPoint);
        if (url.isPresent()) {
            Client client = ClientBuilder.newClient();
            try {
                return Optional.of(client.target(url.get().toURI()));
            } catch (URISyntaxException e) {
                log.severe("Exception while parsing URL. Message: " + e.getMessage());
            }
        }

        return Optional.empty();

    }

    @Produces
    @DiscoverService
    public URL produceUrl(InjectionPoint injectionPoint) {
        Optional<URL> urlOpt = getUrl(injectionPoint);
        if (urlOpt.isPresent()) {
            return urlOpt.get();
        } else {
            throw new ServiceNotFoundException(SERVICE_NOT_FOUNT_MSG);
        }
    }

    @Produces
    @DiscoverService
    public String produceString(InjectionPoint injectionPoint) {
        Optional<String> stringOpt = produceStringOpt(injectionPoint);
        if (stringOpt.isPresent()) {
            return stringOpt.get();
        } else {
            throw new ServiceNotFoundException(SERVICE_NOT_FOUNT_MSG);
        }
    }

    @Produces
    @DiscoverService
    public WebTarget produceWebTarget(InjectionPoint injectionPoint) {
        Optional<WebTarget> webTargetOpt = produceWebTargetOpt(injectionPoint);
        if (webTargetOpt.isPresent()) {
            return webTargetOpt.get();
        } else {
            throw new ServiceNotFoundException(SERVICE_NOT_FOUNT_MSG);
        }
    }

    private Optional<URL> getUrl(InjectionPoint injectionPoint) {

        String serviceName = injectionPoint.getAnnotated().getAnnotation(DiscoverService.class).value();
        String environment = injectionPoint.getAnnotated().getAnnotation(DiscoverService.class).environment();
        String version = injectionPoint.getAnnotated().getAnnotation(DiscoverService.class).version();
        AccessType accessType = injectionPoint.getAnnotated().getAnnotation(DiscoverService.class).accessType();

        if (environment.isEmpty()) {
            environment = EeConfig.getInstance().getEnv().getName();

            if (environment == null || environment.isEmpty()) {
                environment = ConfigurationUtil.getInstance().get("kumuluzee.env").orElse("dev");
            }
        }

        log.log(Level.INFO, "Initializing field for service: {0} version: {1} environment: {2}",
                new String[]{serviceName, version, environment});

        return discoveryUtil.getServiceInstance(serviceName, version, environment, accessType);

    }

}
