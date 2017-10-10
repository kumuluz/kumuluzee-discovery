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

import com.kumuluz.ee.discovery.exceptions.EtcdNotAvailableException;
import mousio.client.retry.RetryPolicy;
import mousio.etcd4j.EtcdClient;
import mousio.etcd4j.requests.EtcdKeyGetRequest;
import mousio.etcd4j.responses.EtcdAuthenticationException;
import mousio.etcd4j.responses.EtcdException;
import mousio.etcd4j.responses.EtcdKeysResponse;

import java.io.IOException;
import java.net.SocketException;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * etcd utils
 *
 * @author Urban Malc
 */
public class Etcd2Utils {
    private static final Logger log = Logger.getLogger(Etcd2Utils.class.getName());

    public static EtcdKeysResponse getEtcdDir(EtcdClient etcd, String key, RetryPolicy retryPolicy,
                                              boolean resilience) {

        EtcdKeysResponse etcdKeysResponse = null;

        if (etcd != null) {

            try {
                EtcdKeyGetRequest request = etcd.getDir(key).recursive();
                if(retryPolicy != null) {
                    request.setRetryPolicy(retryPolicy);
                }

                etcdKeysResponse = request.send().get();
            } catch(SocketException | TimeoutException e) {
                String message = "Timeout exception. Cannot read given key in time";
                if(resilience) {
                    log.severe(message + ": " + e);
                } else {
                    throw new EtcdNotAvailableException(message, e);
                }
            } catch (IOException e) {
                log.info("IO Exception. Cannot read given key: " + e);
            } catch (EtcdException e) {
                log.info("Etcd exception. " + e);
            } catch (EtcdAuthenticationException e) {
                log.severe("Etcd authentication exception. Cannot read given key: " + e);
            }

        } else {
            log.severe("etcd not initialised.");
        }

        return etcdKeysResponse;
    }

    public static EtcdKeysResponse getEtcdDir(EtcdClient etcd, String key, boolean resilience) {
        return getEtcdDir(etcd, key, null, resilience);
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
