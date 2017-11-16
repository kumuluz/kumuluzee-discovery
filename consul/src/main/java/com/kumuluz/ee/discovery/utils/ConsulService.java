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

import com.orbitz.consul.model.health.ServiceHealth;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.logging.Logger;

/**
 * Representation of a service as used by Consul.
 *
 * @author Urban Malc
 * @author Jan Meznaric
 * @since 1.0.0
 */
public class ConsulService {

    private static final Logger log = Logger.getLogger(ConsulService.class.getName());

    public static final String TAG_HTTPS = "https";
    public static final String TAG_VERSION_PREFIX = "version=";

    private String id;
    private URL serviceUrl;
    private String version;

    private ConsulService(String id, URL serviceUrl, String version) {
        this.id = id;
        this.serviceUrl = serviceUrl;
        this.version = version;
    }

    public URL getServiceUrl() {
        return serviceUrl;
    }

    public String getVersion() {
        return version;
    }

    public String getId() {
        return id;
    }

    public static ConsulService getInstanceFromServiceHealth(ServiceHealth serviceHealth) {
        URL url = serviceHealthToURL(serviceHealth);
        if (url != null) {
            String version = null;
            for (String tag : serviceHealth.getService().getTags()) {
                if (tag.startsWith(TAG_VERSION_PREFIX)) {
                    version = tag.substring(TAG_VERSION_PREFIX.length());
                }
            }
            if (version == null || version.isEmpty()) {
                version = "1.0.0";
            }

            return new ConsulService(serviceHealth.getService().getId(), url, version);
        }

        return null;
    }

    private static URL serviceHealthToURL(ServiceHealth serviceHealth) {

        String address = serviceHealth.getService().getAddress();

        if (address == null || address.isEmpty()) {
            address = serviceHealth.getNode().getAddress();
        }

        try {
            return new URL(((serviceHealth.getService().getTags().contains(TAG_HTTPS)) ? "https" : "http")
                    + "://" + address + ":" + serviceHealth.getService().getPort()); //
        } catch (MalformedURLException e) {
            log.severe("Malformed URL when translating serviceHealth to URL: " + e.getLocalizedMessage());
        }

        return null;
    }
}
