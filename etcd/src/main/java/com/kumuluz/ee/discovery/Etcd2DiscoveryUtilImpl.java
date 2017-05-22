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

import com.kumuluz.ee.configuration.utils.ConfigurationUtil;
import com.kumuluz.ee.discovery.utils.DiscoveryUtil;
import com.kumuluz.ee.discovery.utils.Etcd2ServiceConfiguration;
import com.kumuluz.ee.discovery.utils.Etcd2Utils;
import com.kumuluz.ee.discovery.utils.HostAddressComparator;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import mousio.client.promises.ResponsePromise;
import mousio.client.retry.RetryWithExponentialBackOff;
import mousio.etcd4j.EtcdClient;
import mousio.etcd4j.EtcdSecurityContext;
import mousio.etcd4j.promises.EtcdResponsePromise;
import mousio.etcd4j.responses.EtcdAuthenticationException;
import mousio.etcd4j.responses.EtcdErrorCode;
import mousio.etcd4j.responses.EtcdException;
import mousio.etcd4j.responses.EtcdKeysResponse;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.net.ssl.SSLException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.*;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * @author Jan Meznarič, Urban Malc
 */
@ApplicationScoped
public class Etcd2DiscoveryUtilImpl implements DiscoveryUtil {

    private static final Logger log = Logger.getLogger(Etcd2DiscoveryUtilImpl.class.getName());
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final ConfigurationUtil configurationUtil = ConfigurationUtil.getInstance();

    private List<Etcd2ServiceConfiguration> registeredServices;

    private int lastInstanceServedIndex;

    private Map<String, Map<String, URL>> serviceInstances;
    private Map<String, List<String>> serviceVersions;

    private EtcdClient etcd;

    @PostConstruct
    public void init() {

        this.registeredServices = new LinkedList<>();

        this.lastInstanceServedIndex = 0;
        this.serviceInstances = new HashMap<>();
        this.serviceVersions = new HashMap<>();

        // get user credentials
        String etcdUsername = configurationUtil.get("kumuluzee.discovery.etcd.username").orElse(null);
        String etcdPassword = configurationUtil.get("kumuluzee.discovery.etcd.password").orElse(null);

        // get CA certificate
        String cert = configurationUtil.get("kumuluzee.discovery.etcd.ca").orElse(null);
        SslContext sslContext = null;
        if (cert != null) {

            cert = cert.replaceAll("\\s+", "").replace("-----BEGINCERTIFICATE-----", "")
                    .replace("-----ENDCERTIFICATE-----", "");

            byte[] decoded = Base64.getDecoder().decode(cert);

            try {
                X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(decoded));

                sslContext = SslContextBuilder.forClient().trustManager(certificate).build();

            } catch (CertificateException e) {
                log.severe("Certificate exception: " + e.toString());
            } catch (SSLException e) {
                log.severe("SSL exception: " + e.toString());
            }
        }

        // initialize security context
        EtcdSecurityContext etcdSecurityContext = null;
        if (etcdUsername != null && !etcdUsername.isEmpty() && etcdPassword != null && !etcdPassword.isEmpty()) {
            if (sslContext != null) {
                etcdSecurityContext = new EtcdSecurityContext(sslContext, etcdUsername, etcdPassword);
            } else {
                etcdSecurityContext = new EtcdSecurityContext(etcdUsername, etcdPassword);
            }
        } else if (sslContext != null) {
            etcdSecurityContext = new EtcdSecurityContext(sslContext);
        }

        // get etcd host names
        String etcdUrls = configurationUtil.get("kumuluzee.discovery.etcd.hosts").orElse(null);
        if (etcdUrls != null && !etcdUrls.isEmpty()) {

            String[] splittedEtcdUrls = etcdUrls.split(",");
            URI[] etcdHosts = new URI[splittedEtcdUrls.length];
            for (int i = 0; i < etcdHosts.length; i++) {
                etcdHosts[0] = URI.create(splittedEtcdUrls[0]);
            }

            if (etcdHosts.length % 2 == 0) {
                log.warning("Using an odd number of etcd hosts is recommended. See etcd documentation.");
            }

            if (etcdSecurityContext != null) {

                etcd = new EtcdClient(etcdSecurityContext, etcdHosts);

            } else {

                etcd = new EtcdClient(etcdHosts);

            }

            int startRetryDelay = configurationUtil.getInteger("kumuluzee.discovery.etcd.start-retry-delay-ms")
                    .orElse(500);
            int maxRetryDelay = configurationUtil.getInteger("kumuluzee.discovery.etcd.max-retry-delay-ms")
                    .orElse(900000);

            etcd.setRetryHandler(new RetryWithExponentialBackOff(startRetryDelay, -1, maxRetryDelay));

        } else {
            log.severe("No etcd server hosts provided. Specify hosts with configuration key" +
                    "kumuluzee.discovery.etcd.hosts in format " +
                    "http://192.168.99.100:2379,http://192.168.99.101:2379,http://192.168.99.102:2379");
        }
    }

    @Override
    public void register(String serviceName, String version, String environment, long ttl,
                         long pingInterval, boolean singleton) {

        // get service URL
        String baseUrl = configurationUtil.get("kumuluzee.base-url").orElse(null);
        if(baseUrl != null) {
            try {
                baseUrl = new URL(baseUrl).toString();
            } catch (MalformedURLException e) {
                log.severe("Cannot parse kumuluzee.base-url. Exception: " + e.toString());
                baseUrl = null;
            }
        }
        if (baseUrl == null || baseUrl.isEmpty()) {
            // try to find my ip address
            List<InetAddress> interfaceAddresses = new ArrayList<>();
            try {
                Enumeration<NetworkInterface> niEnum = NetworkInterface.getNetworkInterfaces();
                while(niEnum.hasMoreElements()) {
                    NetworkInterface ni = niEnum.nextElement();
                    Enumeration<InetAddress> inetEnum = ni.getInetAddresses();
                    while(inetEnum.hasMoreElements()) {
                        interfaceAddresses.add(inetEnum.nextElement());
                    }
                }
            } catch (SocketException e) {
                e.printStackTrace();
            }
            interfaceAddresses.sort(new HostAddressComparator());
            URL ipUrl = null;
            String servicePort = configurationUtil.get("port").orElse("8080");
            for(int i = 0; i < interfaceAddresses.size() && ipUrl == null; i++) {
                InetAddress addr = interfaceAddresses.get(i);
                try {
                    if (addr instanceof Inet4Address) {
                        ipUrl = new URL("http://" + addr.getHostAddress() + ":" + servicePort);
                    } else {
                        ipUrl = new URL("http://[" + addr.getHostAddress().split("%")[0] + "]:" + servicePort);
                    }
                } catch (MalformedURLException e) {
                    log.severe("Cannot parse URL. Exception: " + e.toString());
                }
            }
            if(ipUrl != null) {
                log.warning("No service URL provided, using ULR " + ipUrl.toString() +
                        ". You should probably set service URL with configuration key kumuluzee.base-url");
                baseUrl = ipUrl.toString();
            } else {
                log.severe("No service URL provided or found." +
                        "Set service URL with configuration key kumuluzee.base-url");
                return;
            }
        }

        Etcd2ServiceConfiguration serviceConfiguration = new Etcd2ServiceConfiguration(serviceName, version,
                environment, (int)ttl, singleton, baseUrl);

        this.registeredServices.add(serviceConfiguration);

        Etcd2Registrator registrator = new Etcd2Registrator(etcd, serviceConfiguration);
        scheduler.scheduleWithFixedDelay(registrator, 0, pingInterval, TimeUnit.SECONDS);

    }

    @Override
    public void deregister() {

        if (etcd != null) {
            for(Etcd2ServiceConfiguration serviceConfiguration : this.registeredServices) {
                log.info("Deregistering service with etcd. Service name: " + serviceConfiguration.getServiceName() +
                        " Service ID: " + serviceConfiguration.getServiceKeyUrl());

                try {
                    etcd.delete(serviceConfiguration.getServiceKeyUrl()).send();
                } catch (IOException e) {
                    log.severe("Cannot deregister service. Error: " + e.toString());
                }
            }
        }
    }

    @Override
    public Optional<List<URL>> getServiceInstances(String serviceName, String version,
                                                   String environment) {

        version = determineVersion(serviceName, version, environment);

        if (!this.serviceInstances.containsKey(serviceName + "_" + version + "_" + environment)) {

            EtcdKeysResponse etcdKeysResponse = Etcd2Utils.getEtcdDir(etcd, Etcd2Utils.getServiceKeyInstances
                    (environment, serviceName, version));

            HashMap<String, URL> serviceUrls = new HashMap<>();
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
                        try {
                            serviceUrls.put(node.getKey() + "/url", new URL(url));
                        } catch (MalformedURLException e) {
                            log.severe("Malformed URL exception: " + e.toString());
                        }
                    }
                }
            }

            this.serviceInstances.put(serviceName + "_" + version + "_" + environment, serviceUrls);

            if (!this.serviceVersions.containsKey(serviceName + "_" + environment)) {
                // we are already watching all versions, no need to watch specific version
                watchServiceInstances(Etcd2Utils.getServiceKeyInstances(environment, serviceName, version),
                        etcdKeysResponse.etcdIndex + 1);
            }

            List<URL> instances = new LinkedList<>();
            instances.addAll(serviceUrls.values());
            return Optional.of(instances);

        } else {

            List<URL> instances = new LinkedList<>();
            instances.addAll(this.serviceInstances.get(serviceName + "_" + version + "_" + environment).values());
            return Optional.of(instances);

        }

    }

    @Override
    public Optional<URL> getServiceInstance(String serviceName, String version, String
            environment) {

        Optional<List<URL>> optionalServiceInstances = getServiceInstances(serviceName, version, environment);

        if (optionalServiceInstances.isPresent()) {

            List<URL> serviceInstances = optionalServiceInstances.get();

            if (!serviceInstances.isEmpty()) {
                int index = 0;
                if (serviceInstances.size() >= lastInstanceServedIndex + 2) {
                    index = lastInstanceServedIndex + 1;
                }
                lastInstanceServedIndex = index;

                return Optional.of(serviceInstances.get(index));
            }
        }

        return Optional.empty();

    }

    @Override
    public Optional<List<String>> getServiceVersions(String serviceName, String environment) {
        if (!this.serviceVersions.containsKey(serviceName + "_" + environment)) {
            EtcdKeysResponse etcdKeysResponse = Etcd2Utils.getEtcdDir(etcd, getServiceKeyVersions(environment,
                    serviceName));

            if (etcdKeysResponse != null) {

                List<String> versions = new LinkedList<>();
                for (EtcdKeysResponse.EtcdNode versionNode : etcdKeysResponse.getNode().getNodes()) {

                    String version = Etcd2Utils.getLastKeyLayer(versionNode.getKey());

                    boolean versionActive = false;
                    for (EtcdKeysResponse.EtcdNode instanceNode : versionNode.getNodes().get(0).getNodes()) {

                        String url = null;
                        String status = null;

                        for (EtcdKeysResponse.EtcdNode node : instanceNode.getNodes()) {

                            if ("url".equals(Etcd2Utils.getLastKeyLayer(node.getKey()))) {
                                url = node.getValue();
                            }

                            if ("status".equals(Etcd2Utils.getLastKeyLayer(node.getKey()))) {
                                status = node.getValue();
                            }
                        }

                        if (url != null && !"disabled".equals(status)) {
                            versionActive = true;

                            // active instance, add to buffer
                            try {
                                if(!this.serviceInstances.containsKey(serviceName + "_" + version + "_" +
                                        environment)) {
                                    this.serviceInstances.put(serviceName + "_" + version + "_" + environment,
                                            new HashMap<>());
                                }
                                this.serviceInstances.get(serviceName + "_" + version + "_" + environment)
                                        .put(instanceNode.getKey() + "/url", new URL(url));
                            } catch (MalformedURLException e) {
                                log.severe("Malformed URL exception: " + e.toString());
                            }
                        }

                    }

                    if (versionActive) {
                        versions.add(version);
                    }
                }

                this.serviceVersions.put(serviceName + "_" + environment, versions);
                watchServiceInstances(getServiceKeyVersions(environment, serviceName),
                        etcdKeysResponse.etcdIndex + 1);
                return Optional.of(versions);
            }
            return Optional.empty();
        } else {
            return Optional.of(this.serviceVersions.get(serviceName + "_" + environment));
        }
    }

    @Override
    public void disableServiceInstance(String serviceName, String version, String
            environment, URL url) {

        String key = Etcd2Utils.getServiceKeyInstances(environment, serviceName, version);

        EtcdKeysResponse etcdKeysResponse = Etcd2Utils.getEtcdDir(etcd, key);
        if (etcdKeysResponse != null) {

            for (EtcdKeysResponse.EtcdNode instance : etcdKeysResponse.getNode().getNodes()) {
                for (EtcdKeysResponse.EtcdNode node : instance.getNodes()) {
                    if ("url".equals(Etcd2Utils.getLastKeyLayer(node.getKey())) &&
                            node.getValue().equals(url.toString())) {
                        log.info("Disabling service instance: " + instance.getKey());
                        putEtcdKey(instance.getKey() + "/status", "disabled");
                    }
                }
            }
        }
    }

    private void watchServiceInstances(String key, long index) {

        if (etcd != null) {

            log.info("Initialising watch for key: " + key);

            EtcdResponsePromise<EtcdKeysResponse> responsePromiseUrl = null;
            try {
                responsePromiseUrl = etcd.getDir(key).recursive().waitForChange(index).send();
            } catch (IOException e) {
                e.printStackTrace();
            }

            responsePromiseUrl.addListener((ResponsePromise<EtcdKeysResponse> promise) -> {
                Throwable t = promise.getException();
                if (t instanceof EtcdException) {
                    if (((EtcdException) t).isErrorCode(EtcdErrorCode.NodeExist)) {
                        log.severe("Exception in etcd promise: " + ((EtcdException) t).etcdMessage);
                    }
                }

                EtcdKeysResponse.EtcdNode node = promise.getNow().getNode();

                // get service name, version and environment from key
                String serviceName = getServiceNameFromKey(node.getKey());
                String version = getVersionFromKey(node.getKey());
                String environment = getEnvironmentFromKey(node.getKey());

                if (serviceName != null && version != null && environment != null) {

                    // url have changed: added or deleted
                    if ("url".equals(Etcd2Utils.getLastKeyLayer(node.getKey()))) {

                        if (node.getValue() == null) {
                            log.info("Service instance deleted: " + node.getKey());
                            this.serviceInstances.get(serviceName + "_" + version + "_" + environment).remove(node
                                    .getKey());
                        } else {
                            log.info("Service instance added: " + node.getKey() + " Value: " + node.getValue());
                            try {
                                if (!this.serviceInstances.containsKey(serviceName + "_" + version + "_" +
                                        environment)) {
                                    this.serviceInstances.put(serviceName + "_" + version + "_" + environment,
                                            new HashMap<>());
                                }
                                this.serviceInstances.get(serviceName + "_" + version + "_" + environment).put(node
                                        .getKey(), new URL(node.getValue()));
                            } catch (MalformedURLException e) {
                                log.severe("Malformed URL exception: " + e.toString());
                            }
                        }

                    }

                    // status has changed: set to disabled
                    if ("status".equals(Etcd2Utils.getLastKeyLayer(node.getKey())) &&
                            "disabled".equals(node.getValue())) {
                        log.info("Service instance disabled: " + node.getKey());
                        this.serviceInstances.get(serviceName + "_" + version + "_" + environment).remove
                                (getKeyOneLayerUp(node.getKey()) + "url");
                    }

                    // node's TTL expired
                    if (node.getTTL() == 0 && this.serviceInstances.containsKey(serviceName + "_" + version + "_" +
                            environment) && this.serviceInstances.get(serviceName + "_" + version + "_" +
                            environment).containsKey(node.getKey() + "/url")) {
                        log.info("Service instance TTL expired: " + node.getKey());
                        this.serviceInstances.get(serviceName + "_" + version + "_" + environment).remove(node.getKey
                                () + "/url");
                    }

                    // if we are watching all versions, update serviceVersions
                    if (isKeyForVersions(key)) {
                        if (this.serviceVersions.containsKey(serviceName + "_" + environment)) {
                            List<String> versions = this.serviceVersions.get(serviceName + "_" + environment);
                            if (versions.contains(version) &&
                                    this.serviceInstances.get(serviceName + "_" + version + "_" + environment)
                                            .isEmpty()) {
                                // version was removed and no other instances of this version exist, remove version
                                versions.remove(version);
                            } else if (!versions.contains(version) &&
                                    (!this.serviceInstances.containsKey(serviceName + "_" + version + "_" +
                                            environment) ||
                                            !this.serviceInstances.get(serviceName + "_" + version + "_" +
                                                    environment).isEmpty())) {
                                // instance of new version was added
                                versions.add(version);
                            }
                        }
                    }

                }

                if (isKeyForVersions(key) || !this.serviceVersions.containsKey(serviceName + "_" + environment)) {
                    // does not set watch if key is for specific version and we are already watching all versions
                    watchServiceInstances(key, node.getModifiedIndex() + 1);
                }
            });

        } else {
            log.severe("etcd not initialised.");
        }
    }

    private boolean isKeyForVersions(String key) {
        return key.split("/").length == 5;
    }

    private String getServiceKeyVersions(String environment, String serviceName) {
        return "/environments/" + environment + "/services/" + serviceName;
    }

    private String getKeyOneLayerUp(String key) {

        String[] splittedKey = key.split("/");

        StringBuilder newKey = new StringBuilder();

        for (int i = 0; i < splittedKey.length - 1; i++) {
            newKey.append(splittedKey[i]).append("/");
        }
        return newKey.toString();
    }

    private String getServiceNameFromKey(String key) {

        String[] splitted = key.split("/");

        if (splitted.length < 4) {
            return null;
        } else {
            return splitted[4];
        }
    }

    private String getVersionFromKey(String key) {

        String[] splitted = key.split("/");

        if (splitted.length < 5) {
            return null;
        } else {
            return splitted[5];
        }

    }

    private String getEnvironmentFromKey(String key) {

        String[] splitted = key.split("/");

        if (splitted.length < 2) {
            return null;
        } else {
            return splitted[2];
        }

    }

    private String determineVersion(String serviceName, String version, String environment) {

        // check, if version has special characters (*, ^, ~)
        // if true, use get getServiceVersions to get appropriate version
        // return version

        Requirement versionRequirement;
        try {
            versionRequirement = Requirement.buildNPM(version);
        } catch (SemverException se) {
            return version;
        }

        if (!version.contains("*") && !version.contains("x")) {
            try {
                new Semver(version, Semver.SemverType.NPM);
                return version;
            } catch (SemverException ignored) {
            }
        }

        Optional<List<String>> versionsOpt = getServiceVersions(serviceName, environment);

        if (versionsOpt.isPresent()) {
            List<String> versions = versionsOpt.get();
            List<Semver> versionsSemver = new LinkedList<>();

            for (String versionString : versions) {
                Semver listVersion;
                try {
                    listVersion = new Semver(versionString, Semver.SemverType.NPM);
                } catch (SemverException se) {
                    continue;
                }

                versionsSemver.add(listVersion);
            }

            Collections.sort(versionsSemver);

            for (int i = versionsSemver.size() - 1; i >= 0; i--) {
                if (versionsSemver.get(i).satisfies(versionRequirement)) {
                    return versionsSemver.get(i).getOriginalValue();
                }
            }
        }

        return version;
    }

    private void putEtcdKey(String key, String value) {

        if (etcd != null) {

            try {
                etcd.put(key, value).send().get();
            } catch (IOException e) {
                log.info("IO Exception. Cannot read given key: " + e);
            } catch (EtcdException e) {
                log.info("Etcd exception. " + e);
            } catch (EtcdAuthenticationException e) {
                log.severe("Etcd authentication exception. Cannot read given key: " + e);
            } catch (TimeoutException e) {
                log.severe("Timeout exception. Cannot read given key time: " + e);
            }

        } else {
            log.severe("etcd not initialised.");
        }

    }
}
