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

import java.util.Date;

/**
 * Service configuration data.
 *
 * @author Urban Malc
 */
public class Etcd2ServiceConfiguration {
    private String serviceName;
    private String serviceVersion;
    private String environment;
    private int ttl;
    private boolean singleton;
    private String baseUrl;
    private String containerUrl;
    private String clusterId;

    private String serviceInstanceKey;
    private String serviceKeyUrl;

    public Etcd2ServiceConfiguration(String serviceName, String serviceVersion, String environment, int ttl,
                                     boolean singleton, String baseUrl, String containerUrl, String clusterId) {
        this.serviceName = serviceName;
        this.serviceVersion = serviceVersion;
        this.environment = environment;
        this.ttl = ttl;
        this.singleton = singleton;
        this.baseUrl = baseUrl;
        this.containerUrl = containerUrl;
        this.clusterId = clusterId;

        this.serviceInstanceKey = Etcd2Utils.getServiceKeyInstance(this.environment, this.serviceName,
                this.serviceVersion, String.valueOf(new Date().getTime()));

        this.serviceKeyUrl = serviceInstanceKey + "/url/";
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getServiceVersion() {
        return serviceVersion;
    }

    public String getEnvironment() {
        return environment;
    }

    public int getTtl() {
        return ttl;
    }

    public boolean isSingleton() {
        return singleton;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getContainerUrl() {
        return containerUrl;
    }

    public String getClusterId() {
        return this.clusterId;
    }

    public String getServiceInstanceKey() {
        return serviceInstanceKey;
    }

    public String getServiceKeyUrl() {
        return serviceKeyUrl;
    }
}
