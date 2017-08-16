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

import com.kumuluz.ee.discovery.utils.Etcd2ServiceConfiguration;
import com.kumuluz.ee.discovery.utils.Etcd2Utils;
import com.kumuluz.ee.logs.LogManager;
import com.kumuluz.ee.logs.Logger;
import mousio.etcd4j.EtcdClient;
import mousio.etcd4j.responses.EtcdAuthenticationException;
import mousio.etcd4j.responses.EtcdException;
import mousio.etcd4j.responses.EtcdKeysResponse;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * Runnable for service registration and heartbeats
 *
 * @author Jan Meznarič, Urban Malc
 */
public class Etcd2Registrator implements Runnable {
    private static final Logger log = LogManager.getLogger(Etcd2Registrator.class.getName());

    private EtcdClient etcd;
    private Etcd2ServiceConfiguration serviceConfig;

    private boolean isRegistered;

    public Etcd2Registrator(EtcdClient etcd, Etcd2ServiceConfiguration serviceConfig) {
        this.etcd = etcd;
        this.serviceConfig = serviceConfig;
    }

    public void run() {
        if (!this.isRegistered) {
            this.registerToEtcd();
        } else {

            log.info("Sending heartbeat. " + this.serviceConfig.getServiceInstanceKey());

            try {
                this.etcd.putDir(this.serviceConfig.getServiceInstanceKey()).prevExist(true)
                        .refresh(this.serviceConfig.getTtl()).send().get();
            } catch (IOException | EtcdAuthenticationException | TimeoutException e) {
                e.printStackTrace();
            } catch (EtcdException e) {
                if (e.isErrorCode(100)) {
                    log.warn("Etcd key not present: " + this.serviceConfig.getServiceInstanceKey() +
                            ". Reregistering service.");

                    this.isRegistered = false;
                    this.registerToEtcd();
                } else {
                    e.printStackTrace();
                }
            }
        }
    }

    private void registerToEtcd() {
        if (this.serviceConfig.isSingleton() && isRegistered()) {

            log.warn("Instance was not registered. Trying to register a singleton microservice instance, but " +
                    "another instance is already registered.");

        } else {

            if (this.etcd != null) {
                log.info("Registering service with etcd. Service ID: " + this.serviceConfig.getServiceKeyUrl());

                try {
                    etcd.putDir(this.serviceConfig.getServiceInstanceKey()).ttl(this.serviceConfig.getTtl())
                            .send().get();
                    etcd.put(this.serviceConfig.getServiceKeyUrl(), this.serviceConfig.getBaseUrl()).send().get();
                    if(this.serviceConfig.getContainerUrl() != null) {
                        etcd.put(this.serviceConfig.getServiceInstanceKey() + "/containerUrl",
                                this.serviceConfig.getContainerUrl()).send().get();
                    }
                    if(this.serviceConfig.getClusterId() != null) {
                        etcd.put(this.serviceConfig.getServiceInstanceKey() + "/clusterId",
                                this.serviceConfig.getClusterId()).send().get();
                    }

                    this.isRegistered = true;
                } catch (IOException e) {
                    log.info("IO Exception. Cannot read given key.", e);
                } catch (EtcdException e) {
                    log.info("Etcd exception.", e);
                } catch (EtcdAuthenticationException e) {
                    log.error("Etcd authentication exception. Cannot read given key.", e);
                } catch (TimeoutException e) {
                    log.error("Timeout exception. Cannot read given key time.", e);
                }
            } else {
                log.error("etcd not initialised.");
            }

        }
    }

    private boolean isRegistered() {
        String serviceInstancesKey = Etcd2Utils.getServiceKeyInstances(this.serviceConfig.getEnvironment(),
                this.serviceConfig.getServiceName(), this.serviceConfig.getServiceVersion());

        EtcdKeysResponse etcdKeysResponse = Etcd2Utils.getEtcdDir(this.etcd, serviceInstancesKey);

        if (etcdKeysResponse != null) {
            for (EtcdKeysResponse.EtcdNode node : etcdKeysResponse.getNode().getNodes()) {

                String url = null;
                boolean isActive = true;
                for (EtcdKeysResponse.EtcdNode instanceNode : node.getNodes()) {

                    if ("url".equals(Etcd2Utils.getLastKeyLayer(instanceNode.getKey())) &&
                            instanceNode.getValue() != null) {
                        url = instanceNode.getValue();
                    }

                    if ("status".equals(Etcd2Utils.getLastKeyLayer(instanceNode.getKey())) &&
                            "disabled".equals(instanceNode.getValue())) {
                        isActive = false;
                    }

                }
                if (isActive && url != null) {
                    return true;
                }
            }
        }

        return false;
    }
}
