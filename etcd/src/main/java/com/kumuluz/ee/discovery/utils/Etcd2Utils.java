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

import com.kumuluz.ee.logs.LogManager;
import com.kumuluz.ee.logs.Logger;
import mousio.etcd4j.EtcdClient;
import mousio.etcd4j.responses.EtcdAuthenticationException;
import mousio.etcd4j.responses.EtcdException;
import mousio.etcd4j.responses.EtcdKeysResponse;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * etcd utils
 *
 * @author Urban Malc
 */
public class Etcd2Utils {
    private static final Logger log = LogManager.getLogger(Etcd2Utils.class.getName());

    public static EtcdKeysResponse getEtcdDir(EtcdClient etcd, String key) {

        EtcdKeysResponse etcdKeysResponse = null;

        if (etcd != null) {

            try {
                etcdKeysResponse = etcd.getDir(key).recursive().send().get();
            } catch (IOException e) {
                log.info("IO Exception. Cannot read given key", e);
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

        return etcdKeysResponse;
    }

    public static String getLastKeyLayer(String key) {
        String[] splittedKey = key.split("/");
        return splittedKey[splittedKey.length - 1];
    }

    public static String getServiceKeyInstance(String environment, String serviceName, String serviceVersion, String
            serviceId) {
        return "/environments/" + environment + "/services/" + serviceName + "/" + serviceVersion + "/instances/" +
                serviceId;
    }

    public static String getServiceKeyInstances(String environment, String serviceName, String serviceVersion) {
        return "/environments/" + environment + "/services/" + serviceName + "/" + serviceVersion + "/instances/";
    }
}
