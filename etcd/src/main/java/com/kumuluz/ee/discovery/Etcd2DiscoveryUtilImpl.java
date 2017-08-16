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
import com.kumuluz.ee.discovery.enums.AccessType;
import com.kumuluz.ee.discovery.utils.*;
import com.kumuluz.ee.logs.LogManager;
import com.kumuluz.ee.logs.Logger;
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

/**
 * @author Jan Meznarič, Urban Malc
 */
@ApplicationScoped
public class Etcd2DiscoveryUtilImpl implements DiscoveryUtil {

    private static final Logger log = LogManager.getLogger(Etcd2DiscoveryUtilImpl.class.getName());
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final ConfigurationUtil configurationUtil = ConfigurationUtil.getInstance();

    private List<Etcd2ServiceConfiguration> registeredServices;

    private int lastInstanceServedIndex;

    private Map<String, Map<String, Etcd2Service>> serviceInstances;
    private Map<String, List<String>> serviceVersions;
    private Map<String, URL> gatewayUrls;

    private EtcdClient etcd;

    private String clusterId;

    @PostConstruct
    public void init() {

        this.registeredServices = new LinkedList<>();

        this.lastInstanceServedIndex = 0;
        this.serviceInstances = new HashMap<>();
        this.serviceVersions = new HashMap<>();
        this.gatewayUrls = new HashMap<>();

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
                log.error("Certificate exception.", e);
            } catch (SSLException e) {
                log.error("SSL exception.", e);
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
                log.warn("Using an odd number of etcd hosts is recommended. See etcd documentation.");
            }

            if (etcdSecurityContext != null) {

                etcd = new EtcdClient(etcdSecurityContext, etcdHosts);

            } else {

                etcd = new EtcdClient(etcdHosts);

            }

            int startRetryDelay = InitializationUtils.getStartRetryDelayMs(configurationUtil, "etcd");
            int maxRetryDelay = InitializationUtils.getMaxRetryDelayMs(configurationUtil, "etcd");

            etcd.setRetryHandler(new RetryWithExponentialBackOff(startRetryDelay, -1, maxRetryDelay));

        } else {
            log.error("No etcd server hosts provided. Specify hosts with configuration key" +
                    "kumuluzee.discovery.etcd.hosts in format " +
                    "http://192.168.99.100:2379,http://192.168.99.101:2379,http://192.168.99.102:2379");
        }

        this.clusterId = configurationUtil.get("kumuluzee.discovery.cluster").orElse(null);
    }

    @Override
    public void register(String serviceName, String version, String environment, long ttl,
                         long pingInterval, boolean singleton) {

        // get service URL
        String baseUrl = configurationUtil.get("kumuluzee.base-url").orElse(null);
        if (baseUrl != null) {
            try {
                baseUrl = new URL(baseUrl).toString();
            } catch (MalformedURLException e) {
                log.error("Cannot parse kumuluzee.base-url.", e);
                baseUrl = null;
            }
        }
        if (baseUrl == null) {
            baseUrl = configurationUtil.get("kumuluzee.baseurl").orElse(null);
            if (baseUrl != null) {
                try {
                    baseUrl = new URL(baseUrl).toString();
                } catch (MalformedURLException e) {
                    log.error("Cannot parse kumuluzee.baseurl.", e);
                    baseUrl = null;
                }
            }
        }
        String containerUrl = configurationUtil.get("kumuluzee.containerurl").orElse(null);
        if (containerUrl != null) {
            try {
                containerUrl = new URL(containerUrl).toString();
            } catch (MalformedURLException e) {
                log.error("Cannot parse kumuluzee.containerurl.", e);
                containerUrl = null;
            }
        }
        if (this.clusterId != null || baseUrl == null || baseUrl.isEmpty()) {
            // try to find my ip address
            List<InetAddress> interfaceAddresses = new ArrayList<>();
            try {
                Enumeration<NetworkInterface> niEnum = NetworkInterface.getNetworkInterfaces();
                while (niEnum.hasMoreElements()) {
                    NetworkInterface ni = niEnum.nextElement();
                    Enumeration<InetAddress> inetEnum = ni.getInetAddresses();
                    while (inetEnum.hasMoreElements()) {
                        interfaceAddresses.add(inetEnum.nextElement());
                    }
                }
            } catch (SocketException e) {
                e.printStackTrace();
            }
            interfaceAddresses.sort(new HostAddressComparator());
            URL ipUrl = null;
            String servicePort = configurationUtil.get("port").orElse("8080");
            for (int i = 0; i < interfaceAddresses.size() && ipUrl == null; i++) {
                InetAddress addr = interfaceAddresses.get(i);
                try {
                    if (addr instanceof Inet4Address) {
                        ipUrl = new URL("http://" + addr.getHostAddress() + ":" + servicePort);
                    } else {
                        ipUrl = new URL("http://[" + addr.getHostAddress().split("%")[0] + "]:" + servicePort);
                    }
                } catch (MalformedURLException e) {
                    log.error("Cannot parse URL.", e);
                }
            }
            if (this.clusterId != null) {
                if (containerUrl == null && ipUrl != null) {
                    containerUrl = ipUrl.toString();
                } else if (containerUrl == null) {
                    log.error("No container URL found, but running in container. All services will use service" +
                            "URL. You can set container URL with configuration key kumuluzee.containerurl");
                }
            }
            if (baseUrl == null || baseUrl.isEmpty()) {
                if (ipUrl != null) {
                    log.warn("No service URL provided, using ULR " + ipUrl.toString() +
                            ". You should probably set service URL with configuration key kumuluzee.base-url or " +
                            "kumuluzee.baseurl");
                    baseUrl = ipUrl.toString();
                } else {
                    log.error("No service URL provided or found." +
                            "Set service URL with configuration key kumuluzee.base-url or kumuluzee.baseurl");
                    return;
                }
            }
        }

        Etcd2ServiceConfiguration serviceConfiguration = new Etcd2ServiceConfiguration(serviceName, version,
                environment, (int) ttl, singleton, baseUrl, containerUrl, this.clusterId);

        this.registeredServices.add(serviceConfiguration);

        Etcd2Registrator registrator = new Etcd2Registrator(etcd, serviceConfiguration);
        scheduler.scheduleWithFixedDelay(registrator, 0, pingInterval, TimeUnit.SECONDS);

    }

    @Override
    public void deregister() {

        if (etcd != null) {
            for (Etcd2ServiceConfiguration serviceConfiguration : this.registeredServices) {
                log.info("Deregistering service with etcd. Service name: " + serviceConfiguration.getServiceName() +
                        " Service ID: " + serviceConfiguration.getServiceKeyUrl());

                try {
                    etcd.delete(serviceConfiguration.getServiceKeyUrl()).send();
                } catch (IOException e) {
                    log.error("Cannot deregister service.", e);
                }
            }
        }
    }

    @Override
    public Optional<List<URL>> getServiceInstances(String serviceName, String version,
                                                   String environment, AccessType accessType) {

        version = CommonUtils.determineVersion(this, serviceName, version, environment);

        if (!this.serviceInstances.containsKey(serviceName + "_" + version + "_" + environment)) {

            EtcdKeysResponse etcdKeysResponse = Etcd2Utils.getEtcdDir(etcd, Etcd2Utils.getServiceKeyInstances
                    (environment, serviceName, version));

            HashMap<String, Etcd2Service> serviceUrls = new HashMap<>();
            if (etcdKeysResponse != null) {
                for (EtcdKeysResponse.EtcdNode node : etcdKeysResponse.getNode().getNodes()) {

                    String url = null;
                    String containerUrlString = null;
                    String clusterId = null;
                    boolean isActive = true;
                    for (EtcdKeysResponse.EtcdNode instanceNode : node.getNodes()) {

                        if ("url".equals(Etcd2Utils.getLastKeyLayer(instanceNode.getKey())) &&
                                instanceNode.getValue() != null) {
                            url = instanceNode.getValue();
                        }

                        if ("containerUrl".equals(Etcd2Utils.getLastKeyLayer(instanceNode.getKey())) &&
                                instanceNode.getValue() != null) {
                            containerUrlString = instanceNode.getValue();
                        }

                        if ("clusterId".equals(Etcd2Utils.getLastKeyLayer(instanceNode.getKey())) &&
                                instanceNode.getValue() != null && !instanceNode.getValue().isEmpty()) {
                            clusterId = instanceNode.getValue();
                        }

                        if ("status".equals(Etcd2Utils.getLastKeyLayer(instanceNode.getKey())) &&
                                "disabled".equals(instanceNode.getValue())) {
                            isActive = false;
                        }

                    }
                    if (isActive && url != null) {
                        try {
                            URL containerUrl = (containerUrlString == null || containerUrlString.isEmpty()) ?
                                    null : new URL(containerUrlString);
                            serviceUrls.put(node.getKey() + "/url",
                                    new Etcd2Service(new URL(url), containerUrl, clusterId));
                        } catch (MalformedURLException e) {
                            log.error("Malformed URL exception.", e);
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

            URL gatewayUrl = getGatewayUrl(serviceName, version, environment);
            if (accessType == AccessType.GATEWAY && gatewayUrl != null && serviceUrls.size() > 0) {
                instances.add(gatewayUrl);
            } else {
                for (Etcd2Service service : serviceUrls.values()) {
                    if (this.clusterId != null && this.clusterId.equals(service.getClusterId())) {
                        instances.add(service.getContainerUrl());
                    } else {
                        instances.add(service.getBaseUrl());
                    }
                }
            }
            return Optional.of(instances);

        } else {

            List<URL> instances = new LinkedList<>();

            URL gatewayUrl = getGatewayUrl(serviceName, version, environment);
            if (accessType == AccessType.GATEWAY && gatewayUrl != null &&
                    this.serviceInstances.get(serviceName + "_" + version + "_" + environment).size() > 0) {
                instances.add(gatewayUrl);
            } else {
                for (Etcd2Service service : this.serviceInstances.get(serviceName + "_" + version + "_" + environment)
                        .values()) {
                    if (this.clusterId != null && this.clusterId.equals(service.getClusterId())) {
                        instances.add(service.getContainerUrl());
                    } else {
                        instances.add(service.getBaseUrl());
                    }
                }
            }
            return Optional.of(instances);

        }

    }

    private URL getGatewayUrl(String serviceName, String version, String environment) {
        if (!this.gatewayUrls.containsKey(serviceName + "_" + version + "_" + environment)) {
            URL gatewayUrl = null;

            long index = 0;
            try {
                EtcdKeysResponse etcdKeysResponse = etcd.get(getGatewayKey(environment, serviceName, version)).send()
                        .get();
                index = etcdKeysResponse.getNode().getModifiedIndex();

                gatewayUrl = new URL(etcdKeysResponse.getNode().getValue());
            } catch (MalformedURLException e) {
                log.error("Malformed URL exception.", e);
            } catch (IOException e) {
                log.info("IO Exception. Cannot read given key.", e);
            } catch (EtcdException e) {
                // ignore key not found exception
                if (e.getErrorCode() != 100) {
                    log.info("Etcd exception.", e);
                }
            } catch (EtcdAuthenticationException e) {
                log.error("Etcd authentication exception. Cannot read given key.", e);
            } catch (TimeoutException e) {
                log.error("Timeout exception. Cannot read given key time.", e);
            }

            this.gatewayUrls.put(serviceName + "_" + version + "_" + environment, gatewayUrl);
            watchServiceInstances(getGatewayKey(environment, serviceName, version), index + 1);

            return gatewayUrl;
        } else {
            return this.gatewayUrls.get(serviceName + "_" + version + "_" + environment);
        }
    }

    @Override
    public Optional<URL> getServiceInstance(String serviceName, String version, String
            environment, AccessType accessType) {

        Optional<List<URL>> optionalServiceInstances = getServiceInstances(serviceName, version, environment,
                accessType);

        return optionalServiceInstances.flatMap(CommonUtils::pickServiceInstanceRoundRobin);
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

                    EtcdKeysResponse.EtcdNode instanceParentNode = null;
                    for(EtcdKeysResponse.EtcdNode instanceParentNodeCandidate : versionNode.getNodes()) {
                        if(Etcd2Utils.getLastKeyLayer(instanceParentNodeCandidate.key).equals("instances")) {
                            instanceParentNode = instanceParentNodeCandidate;
                            break;
                        }
                    }
                    if(instanceParentNode == null) {
                        continue;
                    }

                    boolean versionActive = false;
                    for (EtcdKeysResponse.EtcdNode instanceNode : instanceParentNode.getNodes()) {

                        String url = null;
                        String status = null;
                        String containerUrlString = null;
                        String clusterId = null;

                        for (EtcdKeysResponse.EtcdNode node : instanceNode.getNodes()) {

                            if ("url".equals(Etcd2Utils.getLastKeyLayer(node.getKey())) &&
                                    node.getValue() != null) {
                                url = node.getValue();
                            }

                            if ("containerUrl".equals(Etcd2Utils.getLastKeyLayer(node.getKey())) &&
                                    node.getValue() != null) {
                                containerUrlString = node.getValue();
                            }

                            if ("clusterId".equals(Etcd2Utils.getLastKeyLayer(node.getKey())) &&
                                    node.getValue() != null && !node.getValue().isEmpty()) {
                                clusterId = node.getValue();
                            }

                            if ("status".equals(Etcd2Utils.getLastKeyLayer(node.getKey())) &&
                                    node.getValue() != null) {
                                status = node.getValue();
                            }
                        }

                        if (url != null && !"disabled".equals(status)) {
                            versionActive = true;

                            // active instance, add to buffer
                            try {
                                if (!this.serviceInstances.containsKey(serviceName + "_" + version + "_" +
                                        environment)) {
                                    this.serviceInstances.put(serviceName + "_" + version + "_" + environment,
                                            new HashMap<>());
                                }
                                URL containerUrl = (containerUrlString == null || containerUrlString.isEmpty()) ?
                                        null : new URL(containerUrlString);
                                this.serviceInstances.get(serviceName + "_" + version + "_" + environment)
                                        .put(instanceNode.getKey() + "/url",
                                                new Etcd2Service(new URL(url), containerUrl, clusterId));
                            } catch (MalformedURLException e) {
                                log.error("Malformed URL exception.", e);
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
                        log.error("Exception in etcd promise.", t);
                    }
                    if(((EtcdException) t).isErrorCode(EtcdErrorCode.EventIndexCleared)) {
                        // index to old, reset watch to new index
                        watchServiceInstances(key, ((EtcdException) t).getIndex());
                        return;
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
                                Etcd2Service etcd2Service = new Etcd2Service(new URL(node.getValue()), null,
                                        null);
                                if (this.serviceInstances.get(serviceName + "_" + version + "_" + environment)
                                        .containsKey(node.getKey())) {
                                    etcd2Service.setContainerUrl(this.serviceInstances.get(serviceName + "_" + version
                                            + "_" + environment).get(node.getKey()).getContainerUrl());
                                    etcd2Service.setClusterId(this.serviceInstances.get(serviceName + "_" + version
                                            + "_" + environment).get(node.getKey()).getClusterId());
                                }
                                this.serviceInstances.get(serviceName + "_" + version + "_" + environment).put(node
                                        .getKey(), etcd2Service);
                            } catch (MalformedURLException e) {
                                log.error("Malformed URL exception.", e);
                            }
                        }

                    }

                    // container url added or deleted
                    if ("containerUrl".equals(Etcd2Utils.getLastKeyLayer(node.getKey()))) {
                        if (node.getValue() == null) {
                            Etcd2Service service = this.serviceInstances.get(serviceName + "_" + version + "_" +
                                    environment).get(getKeyOneLayerUp(node.getKey()) + "url");
                            if (service != null) {
                                log.info("Service container url deleted: " + node.getKey());
                                service.setContainerUrl(null);
                                this.serviceInstances.get(serviceName + "_" + version + "_" +
                                        environment).put(getKeyOneLayerUp(node.getKey()) + "url", service);
                            }
                        } else {
                            log.info("Service container url added: " + node.getKey() + " Value: " + node.getValue());
                            try {
                                if (!this.serviceInstances.containsKey(serviceName + "_" + version + "_" +
                                        environment)) {
                                    this.serviceInstances.put(serviceName + "_" + version + "_" + environment,
                                            new HashMap<>());
                                }
                                String instanceMapKey = getKeyOneLayerUp(node.getKey()) + "url";
                                Etcd2Service etcd2Service = new Etcd2Service(null, new URL(node.getValue()),
                                        null);
                                if (this.serviceInstances.get(serviceName + "_" + version + "_" + environment)
                                        .containsKey(instanceMapKey)) {
                                    etcd2Service.setBaseUrl(this.serviceInstances.get(serviceName + "_" + version
                                            + "_" + environment).get(instanceMapKey).getBaseUrl());
                                    etcd2Service.setClusterId(this.serviceInstances.get(serviceName + "_" + version
                                            + "_" + environment).get(instanceMapKey).getClusterId());
                                }
                                this.serviceInstances.get(serviceName + "_" + version + "_" + environment)
                                        .put(instanceMapKey, etcd2Service);
                            } catch (MalformedURLException e) {
                                log.error("Malformed URL exception.", e);
                            }
                        }
                    }

                    if ("clusterId".equals(Etcd2Utils.getLastKeyLayer(node.getKey()))) {
                        if (node.getValue() == null) {
                            Etcd2Service service = this.serviceInstances.get(serviceName + "_" + version + "_" +
                                    environment).get(getKeyOneLayerUp(node.getKey()) + "url");
                            if (service != null) {
                                log.info("Service container id deleted: " + node.getKey());
                                service.setClusterId(null);
                                this.serviceInstances.get(serviceName + "_" + version + "_" +
                                        environment).put(getKeyOneLayerUp(node.getKey()) + "url", service);
                            }
                        } else {
                            log.info("Service container id added: " + node.getKey() + " Value: " + node.getValue());

                            if (!this.serviceInstances.containsKey(serviceName + "_" + version + "_" +
                                    environment)) {
                                this.serviceInstances.put(serviceName + "_" + version + "_" + environment,
                                        new HashMap<>());
                            }
                            String instanceMapKey = getKeyOneLayerUp(node.getKey()) + "url";
                            Etcd2Service etcd2Service = new Etcd2Service(null, null,
                                    node.getValue());
                            if (this.serviceInstances.get(serviceName + "_" + version + "_" + environment)
                                    .containsKey(instanceMapKey)) {
                                etcd2Service.setBaseUrl(this.serviceInstances.get(serviceName + "_" + version
                                        + "_" + environment).get(instanceMapKey).getBaseUrl());
                                etcd2Service.setContainerUrl(this.serviceInstances.get(serviceName + "_" + version
                                        + "_" + environment).get(instanceMapKey).getContainerUrl());
                            }
                            this.serviceInstances.get(serviceName + "_" + version + "_" + environment)
                                    .put(instanceMapKey, etcd2Service);
                        }
                    }

                    // gatewayUrl changed: added, modified or deleted
                    if ("gatewayUrl".equals(Etcd2Utils.getLastKeyLayer(node.getKey()))) {
                        if (node.getValue() == null &&
                                this.gatewayUrls.containsKey(serviceName + "_" + version + "_" + environment)) {
                            log.info("Gateway URL deleted: " + node.getKey());
                            this.gatewayUrls.remove(serviceName + "_" + version + "_" + environment);
                        } else {
                            log.info("Gateway URL added or modified: " + node.getKey() + " Value: " +
                                    node.getValue());

                            URL gatewayUrl = null;

                            try {
                                gatewayUrl = new URL(node.getValue());
                            } catch (MalformedURLException e) {
                                log.error("Malformed URL exception.", e);
                            }

                            this.gatewayUrls.put(serviceName + "_" + version + "_" + environment, gatewayUrl);
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
            log.error("etcd not initialised.");
        }
    }

    private boolean isKeyForVersions(String key) {
        return key.split("/").length == 5;
    }

    private String getServiceKeyVersions(String environment, String serviceName) {
        return "/environments/" + environment + "/services/" + serviceName;
    }

    private String getGatewayKey(String environment, String serviceName, String version) {
        return "/environments/" + environment + "/services/" + serviceName + "/" + version + "/gatewayUrl";
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

    private void putEtcdKey(String key, String value) {

        if (etcd != null) {

            try {
                etcd.put(key, value).send().get();
            } catch (IOException e) {
                log.info("IO Exception. Cannot read given key.", e);
            } catch (EtcdException e) {
                log.info("Etcd exception.", e);
            } catch (EtcdAuthenticationException e) {
                log.error("Etcd authentication exception. Cannot read given key: ", e);
            } catch (TimeoutException e) {
                log.error("Timeout exception. Cannot read given key time: ", e);
            }

        } else {
            log.error("etcd not initialised.");
        }

    }
}
