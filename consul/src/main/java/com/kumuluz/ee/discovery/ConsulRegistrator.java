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

import com.kumuluz.ee.discovery.utils.ConsulService;
import com.kumuluz.ee.discovery.utils.ConsulServiceConfiguration;
import com.orbitz.consul.AgentClient;
import com.orbitz.consul.ConsulException;
import com.orbitz.consul.HealthClient;
import com.orbitz.consul.NotRegisteredException;
import com.orbitz.consul.model.agent.ImmutableRegCheck;
import com.orbitz.consul.model.agent.Registration;
import com.orbitz.consul.model.health.ServiceHealth;

import java.util.List;
import java.util.logging.Logger;

/**
 * Runnable for service registration and heartbeats
 *
 * @author Urban Malc
 * @author Jan Meznaric
 * @since 1.0.0
 */
public class ConsulRegistrator implements Runnable {
    private static final Logger log = Logger.getLogger(ConsulRegistrator.class.getName());

    private AgentClient agentClient;
    private HealthClient healthClient;
    private ConsulServiceConfiguration serviceConfiguration;

    private boolean isRegistered;

    private int currentRetryDelay;

    public ConsulRegistrator(AgentClient agentClient, HealthClient healthClient,
                             ConsulServiceConfiguration serviceConfiguration) {
        this.agentClient = agentClient;
        this.healthClient = healthClient;
        this.serviceConfiguration = serviceConfiguration;

        this.isRegistered = false;

        this.currentRetryDelay = serviceConfiguration.getStartRetryDelay();
    }

    @Override
    public void run() {
        if (!this.isRegistered) {
            this.registerToConsul();
        } else {
            sendHeartbeat();
        }
    }

    private void sendHeartbeat() {
        log.info("Sending heartbeat.");
        try {
            agentClient.pass(this.serviceConfiguration.getServiceId());
        } catch (NotRegisteredException e) {
            log.warning("Received NotRegisteredException from Consul AgentClient when sending heartbeat. " +
                    "Reregistering service.");
            this.isRegistered = false;
            this.registerToConsul();
        }
    }

    private void registerToConsul() {
        if (this.serviceConfiguration.isSingleton() && isRegistered()) {
            log.warning("Instance was not registered. Trying to register a singleton microservice instance, but " +
                    "another instance is already registered.");
        } else {
            log.info("Registering service with Consul. Service name: " + this.serviceConfiguration.getServiceName() +
                    " Service ID: " + this.serviceConfiguration.getServiceId());

            if (agentClient != null) {
                while (!this.isRegistered) {
                    try {
                        ImmutableRegCheck.Builder ttlCheckBuilder = ImmutableRegCheck.builder()
                                .ttl(String.format("%ss", this.serviceConfiguration.getTtl()));

                        if (this.serviceConfiguration.getDeregisterCriticalServiceAfter() != 0) {
                            ttlCheckBuilder = ttlCheckBuilder.deregisterCriticalServiceAfter(String
                                    .format("%ss", this.serviceConfiguration.getDeregisterCriticalServiceAfter()));
                        }
                        Registration.RegCheck ttlCheck = ttlCheckBuilder.build();

                        agentClient.register(this.serviceConfiguration.getServicePort(), ttlCheck,
                                this.serviceConfiguration.getServiceConsulKey(),
                                this.serviceConfiguration.getServiceId(),
                                this.serviceConfiguration.getServiceProtocol(),
                                ConsulService.TAG_VERSION_PREFIX + this.serviceConfiguration.getVersion());
                        this.isRegistered = true;
                        this.currentRetryDelay = serviceConfiguration.getStartRetryDelay();
                    } catch (ConsulException e) {
                        log.severe("Consul Exception when registering service: " + e.getLocalizedMessage());
                        try {
                            Thread.sleep(currentRetryDelay);
                        } catch (InterruptedException ignored) {
                        }

                        // exponential increase, limited by maxRetryDelay
                        currentRetryDelay *= 2;
                        if (currentRetryDelay > serviceConfiguration.getMaxRetryDelay()) {
                            currentRetryDelay = serviceConfiguration.getMaxRetryDelay();
                        }
                    }
                }

                // we need to send heartbeat immediately after registration so the checks pass
                sendHeartbeat();
            } else {
                log.severe("Consul not initialized.");
            }
        }
    }

    private boolean isRegistered() {
        if (healthClient != null) {
            List<ServiceHealth> serviceInstances;
            try {
                serviceInstances = healthClient
                        .getHealthyServiceInstances(this.serviceConfiguration.getServiceConsulKey()).getResponse();
            } catch (ConsulException e) {
                log.severe("Error retrieving healthy instances from Consul. Cannot determine, if service is " +
                        "already registered. ConsulException: " + e.getLocalizedMessage());
                return true;
            }

            boolean registered = false;

            for (ServiceHealth serviceHealth : serviceInstances) {
                ConsulService consulService = ConsulService.getInstanceFromServiceHealth(serviceHealth);
                if (consulService != null && consulService.getVersion().equals(this.serviceConfiguration.getVersion()
                )) {
                    registered = true;
                    break;
                }
            }

            return registered;
        } else {
            log.severe("Consul not initialized");
            return false;
        }
    }
}
