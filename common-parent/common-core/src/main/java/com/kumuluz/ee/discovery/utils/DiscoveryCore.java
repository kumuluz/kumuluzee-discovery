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

import com.kumuluz.ee.discovery.enums.AccessType;

import java.net.URL;
import java.util.List;
import java.util.Optional;

/**
 * Interface for service discovery.
 *
 * @author Jan Meznaric
 * @since 1.0.0
 */
public interface DiscoveryCore {

    /**
     * Registers service instance
     *
     * @param serviceName service name
     * @param version service version
     * @param environment service environment
     * @param ttl instance TTL
     * @param pingInterval refresh interval
     * @param singleton is service singleton
     * @param baseUrl base URL of the instance
     * @param serviceId unique service ID
     * @param containerUrl url of the container
     * @param servicePort port of the service
     */
    void register(String serviceName, String version, String environment, long ttl,
                  long pingInterval, boolean singleton, String baseUrl, String serviceId, String containerUrl, Integer servicePort);

    /**
     * Deregisters all instances, registered with the register(...) methods.
     */
    void deregister();

    /**
     * Deregisters instance with particular instance ID.
     *
     * @param instanceId instance ID
     */
    void deregister(String instanceId);

    Optional<List<URL>> getServiceInstances(String serviceName, String version, String environment,
                                            AccessType accessType);

    /**
     * Return service instance.
     *
     * @param serviceName service name
     * @param version     service version
     * @param environment service environment
     * @param accessType  access type: direct or gateway
     * @return
     */
    Optional<URL> getServiceInstance(String serviceName, String version, String environment, AccessType accessType);


    Optional<List<String>> getServiceVersions(String serviceName, String environment);

    void disableServiceInstance(String serviceName, String version, String environment, URL url);
}
