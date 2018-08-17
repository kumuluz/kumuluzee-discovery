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
package com.kumuluz.ee.discovery;

import com.kumuluz.ee.common.config.EeConfig;
import com.kumuluz.ee.common.runtime.EeRuntime;
import com.kumuluz.ee.configuration.utils.ConfigurationUtil;
import com.kumuluz.ee.discovery.enums.AccessType;
import com.kumuluz.ee.discovery.utils.CommonUtils;
import com.kumuluz.ee.discovery.utils.DiscoveryUtil;
import com.kumuluz.ee.discovery.utils.InitializationUtils;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Implementation of a DiscoveryUtil interface.
 *
 * @author Urban Malc
 * @author Jan Meznaric
 * @since 1.0.0
 */
@ApplicationScoped
public class Etcd2DiscoveryUtilImpl implements DiscoveryUtil {

    private static final Logger log = Logger.getLogger(Etcd2DiscoveryUtilImpl.class.getName());
    private final ConfigurationUtil configurationUtil = ConfigurationUtil.getInstance();
    private Etcd2DiscoveryCoreImpl core;

    @PostConstruct
    public void init() {
        // get user credentials
        String etcdUsername = configurationUtil.get("kumuluzee.discovery.etcd.username").orElse(null);
        String etcdPassword = configurationUtil.get("kumuluzee.discovery.etcd.password").orElse(null);

        // get CA certificate
        String cert = configurationUtil.get("kumuluzee.discovery.etcd.ca").orElse(null);

        // get etcd host names
        String etcdUrls = configurationUtil.get("kumuluzee.discovery.etcd.hosts").orElse(null);

        boolean resilience = configurationUtil.getBoolean("kumuluzee.discovery.resilience").orElse(true);

        int startRetryDelay = InitializationUtils.getStartRetryDelayMs(configurationUtil, "etcd");
        int maxRetryDelay = InitializationUtils.getMaxRetryDelayMs(configurationUtil, "etcd");

        int initialRetryCount = configurationUtil.getInteger("kumuluzee.discovery.etcd.initial-retry-count").orElse(1);

        String clusterId = configurationUtil.get("kumuluzee.discovery.cluster").orElse(null);

        this.core = new Etcd2DiscoveryCoreImpl();
        core.init(etcdUsername, etcdPassword, cert, etcdUrls, resilience, startRetryDelay, maxRetryDelay, initialRetryCount, clusterId);
    }

    @Override
    public void register(String serviceName, String version, String environment, long ttl,
                         long pingInterval, boolean singleton, String baseUrl, String serviceId) {

        EeConfig eeConfig = EeConfig.getInstance();

        String containerUrl = configurationUtil.get("kumuluzee.container-url").orElse(null);
        // get service port
        Integer servicePort = eeConfig.getServer().getHttp().getPort();
        if (servicePort == null) {
            servicePort = EeConfig.getInstance().getServer().getHttps().getPort();
        }
        if (servicePort == null) {
            servicePort = configurationUtil.getInteger("port").orElse(8080);
        }

        if (baseUrl == null) {
            // get service URL
            baseUrl = eeConfig.getServer().getBaseUrl();
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = configurationUtil.get("kumuluzee.base-url").orElse(null);
            }
        }

        if (serviceId == null) {
            serviceId = EeRuntime.getInstance().getInstanceId();

        }

        core.register(serviceName, version, environment, ttl, pingInterval, singleton, baseUrl, serviceId, containerUrl, servicePort);
    }

    @Override
    public void register(String serviceName, String version, String environment, long ttl, long pingInterval, boolean
            singleton) {

        register(serviceName, version, environment, ttl, pingInterval, singleton, null, null);

    }

    @Override
    public void deregister() {

        core.deregister();
    }

    @Override
    public void deregister(String serviceId) {

        core.deregister(serviceId);
    }

    @Override
    public Optional<List<URL>> getServiceInstances(String serviceName, String version,
                                                   String environment, AccessType accessType) {

        version = CommonUtils.determineVersion(this, serviceName, version, environment);
        return core.getServiceInstances(serviceName, version, environment, accessType);
    }

    @Override
    public Optional<URL> getServiceInstance(String serviceName, String version, String
            environment, AccessType accessType) {

        Optional<List<URL>> optionalServiceInstances = getServiceInstances(serviceName, version, environment,
                accessType);

        return optionalServiceInstances.flatMap(CommonUtils::pickServiceInstanceRoundRobin);
    }

    @Override
    public Optional<URL> getServiceInstance(String serviceName, String version, String environment) {

        return getServiceInstance(serviceName, version, environment, AccessType.DIRECT);

    }

    @Override
    public Optional<List<String>> getServiceVersions(String serviceName, String environment) {

        return core.getServiceVersions(serviceName, environment);
    }

    @Override
    public void disableServiceInstance(String serviceName, String version, String
            environment, URL url) {

        core.disableServiceInstance(serviceName, version, environment, url);
    }
}