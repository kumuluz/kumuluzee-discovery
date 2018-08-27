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
import com.kumuluz.ee.configuration.utils.ConfigurationUtil;
import com.kumuluz.ee.discovery.enums.AccessType;
import com.kumuluz.ee.discovery.exceptions.EtcdNotAvailableException;
import com.kumuluz.ee.discovery.utils.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import mousio.client.promises.ResponsePromise;
import mousio.client.retry.RetryNTimes;
import mousio.client.retry.RetryOnce;
import mousio.client.retry.RetryPolicy;
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
import java.util.concurrent.*;
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
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final ConfigurationUtil configurationUtil = ConfigurationUtil.getInstance();

    private List<Etcd2ServiceConfiguration> registeredServices;
    private Map<String, ScheduledFuture> registratorHandles;

    private Map<String, Map<String, Etcd2Service>> serviceInstances;
    private Map<String, List<String>> serviceVersions;
    private Map<String, URL> gatewayUrls;

    private Map<String, Etcd2Service> lastKnownServices;
    private Map<String, String> lastKnownVersions;

    private EtcdClient etcd;
    private RetryPolicy initialRequestRetryPolicy;

    private String clusterId;

    private boolean resilience;

    @PostConstruct
    public void init() {

        this.registeredServices = new LinkedList<>();
        this.registratorHandles = new HashMap<>();

        this.serviceInstances = new HashMap<>();
        this.serviceVersions = new HashMap<>();
        this.gatewayUrls = new HashMap<>();
        this.lastKnownServices = new HashMap<>();
        this.lastKnownVersions = new HashMap<>();

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
                etcdHosts[i] = URI.create(splittedEtcdUrls[i]);
            }

            if (etcdHosts.length % 2 == 0) {
                log.warning("Using an odd number of etcd hosts is recommended. See etcd documentation.");
            }

            if (etcdSecurityContext != null) {

                etcd = new EtcdClient(etcdSecurityContext, etcdHosts);

            } else {

                etcd = new EtcdClient(etcdHosts);

            }

            this.resilience = configurationUtil.getBoolean("kumuluzee.discovery.resilience").orElse(true);

            int startRetryDelay = InitializationUtils.getStartRetryDelayMs(configurationUtil, "etcd");
            int maxRetryDelay = InitializationUtils.getMaxRetryDelayMs(configurationUtil, "etcd");

            RetryPolicy defaultRetryPolicy = new RetryWithExponentialBackOff(startRetryDelay, -1,
                    maxRetryDelay);
            etcd.setRetryHandler(defaultRetryPolicy);

            RetryPolicy zeroRetryPolicy = new RetryNTimes(1, 0);

            int initialRetryCount = configurationUtil.getInteger("kumuluzee.discovery.etcd.initial-retry-count")
                    .orElse(1);
            if (initialRetryCount == 0) {
                this.initialRequestRetryPolicy = zeroRetryPolicy;
            } else if (initialRetryCount > 0) {
                this.initialRequestRetryPolicy = new RetryWithExponentialBackOff(startRetryDelay, initialRetryCount,
                        maxRetryDelay);
            } else {
                this.initialRequestRetryPolicy = defaultRetryPolicy;
            }

            if (!resilience) {
                // set default and initial request retry policies to zero retry
                etcd.setRetryHandler(zeroRetryPolicy);
                this.initialRequestRetryPolicy = zeroRetryPolicy;
            }

        } else {
            log.severe("No etcd server hosts provided. Specify hosts with configuration key" +
                    "kumuluzee.discovery.etcd.hosts in format " +
                    "http://192.168.99.100:2379,http://192.168.99.101:2379,http://192.168.99.102:2379");
        }

        this.clusterId = configurationUtil.get("kumuluzee.discovery.cluster").orElse(null);
    }

    @Override
    public void register(String serviceName, String version, String environment, long ttl,
                         long pingInterval, boolean singleton, String baseUrl, String serviceId) {

        EeConfig eeConfig = EeConfig.getInstance();

        if (baseUrl == null) {
            // get service URL
            baseUrl = eeConfig.getServer().getBaseUrl();
            if (baseUrl == null || baseUrl.isEmpty()) {
                baseUrl = configurationUtil.get("kumuluzee.base-url").orElse(null);
                if (baseUrl != null) {
                    try {
                        baseUrl = new URL(baseUrl).toString();
                    } catch (MalformedURLException e) {
                        log.severe("Cannot parse kumuluzee.base-url. Exception: " + e.toString());
                        baseUrl = null;
                    }
                }
            }
        }

        String containerUrl = configurationUtil.get("kumuluzee.container-url").orElse(null);
        if (containerUrl != null) {
            try {
                containerUrl = new URL(containerUrl).toString();
            } catch (MalformedURLException e) {
                log.severe("Cannot parse kumuluzee.container-url. Exception: " + e.toString());
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

            // get service port
            Integer servicePort = eeConfig.getServer().getHttp().getPort();
            if (servicePort == null) {
                servicePort = EeConfig.getInstance().getServer().getHttps().getPort();
            }
            if (servicePort == null) {
                servicePort = configurationUtil.getInteger("port").orElse(8080);
            }

            for (int i = 0; i < interfaceAddresses.size() && ipUrl == null; i++) {
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
            if (this.clusterId != null) {
                if (containerUrl == null && ipUrl != null) {
                    containerUrl = ipUrl.toString();
                } else if (containerUrl == null) {
                    log.severe("No container URL found, but running in container. All services will use service" +
                            "URL. You can set container URL with configuration key kumuluzee.container-url");
                }
            }
            if (baseUrl == null || baseUrl.isEmpty()) {
                if (ipUrl != null) {
                    log.warning("No service URL provided, using URL " + ipUrl.toString() +
                            ". You should probably set service URL with configuration key kumuluzee.server.base-url");
                    baseUrl = ipUrl.toString();
                } else {
                    log.severe("No service URL provided or found." +
                            "Set service URL with configuration key kumuluzee.server.base-url");
                    return;
                }
            }
        }

        Etcd2ServiceConfiguration serviceConfiguration = new Etcd2ServiceConfiguration(serviceName, version,
                environment, (int) ttl, singleton, baseUrl, containerUrl, this.clusterId, serviceId);

        this.registeredServices.add(serviceConfiguration);

        Etcd2Registrator registrator = new Etcd2Registrator(etcd, serviceConfiguration, resilience);
        ScheduledFuture handle = scheduler.scheduleWithFixedDelay(registrator, 0, pingInterval, TimeUnit.SECONDS);
        this.registratorHandles.put(serviceId, handle);
    }

    @Override
    public void register(String serviceName, String version, String environment, long ttl, long pingInterval, boolean
            singleton) {

        register(serviceName, version, environment, ttl, pingInterval, singleton, null, null);

    }

    @Override
    public void deregister() {

        for (ScheduledFuture handle : this.registratorHandles.values()) {
            handle.cancel(true);
        }

        if (etcd != null) {
            for (Etcd2ServiceConfiguration serviceConfiguration : this.registeredServices) {
                log.info("Deregistering service with etcd. Service name: " + serviceConfiguration.getServiceName() +
                        " Service ID: " + serviceConfiguration.getServiceKeyUrl());

                try {
                    etcd.deleteDir(serviceConfiguration.getServiceInstanceKey()).recursive()
                            .setRetryPolicy(new RetryOnce(0))
                            .send().get();
                } catch (IOException | EtcdException | EtcdAuthenticationException | TimeoutException e) {
                    log.severe("Cannot deregister service. Error: " + e.toString());
                }
            }

            log.info("Closing etcd connection for Discovery extension.");
            try {
                etcd.close();
                etcd = null;
            } catch (IOException e) {
                log.severe("Could not close etcd extension. Exception: " + e.getMessage());
            }
        }
    }

    @Override
    public void deregister(String serviceId) {

        log.info("Deregistering service with etcd. Service id: " + serviceId);

        ScheduledFuture handle = this.registratorHandles.remove(serviceId);
        if (handle != null) {
            handle.cancel(true);
        }

        if (etcd != null) {

            for (Etcd2ServiceConfiguration service : this.registeredServices) {
                if (service.getServiceInstanceKey().endsWith(serviceId)) {
                    try {
                        etcd.deleteDir(service.getServiceInstanceKey()).recursive()
                                .setRetryPolicy(new RetryOnce(0))
                                .send().get();
                    } catch (IOException | EtcdException | EtcdAuthenticationException | TimeoutException e) {
                        log.severe("Cannot deregister service. Error: " + e.toString());
                    }
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
                    (environment, serviceName, version), this.initialRequestRetryPolicy, this.resilience);

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
                            log.severe("Malformed URL exception: " + e.toString());
                        }
                    }
                }

                this.serviceInstances.put(serviceName + "_" + version + "_" + environment, serviceUrls);

                if (!this.serviceVersions.containsKey(serviceName + "_" + environment)) {
                    // we are already watching all versions, no need to watch specific version
                    watchServiceInstances(Etcd2Utils.getServiceKeyInstances(environment, serviceName, version),
                            etcdKeysResponse.etcdIndex + 1);
                }
            }
        }

        Map<String, Etcd2Service> presentServices = this.serviceInstances
                .get(serviceName + "_" + version + "_" + environment);
        if ((presentServices == null || presentServices.size() == 0) && this.lastKnownServices
                .containsKey(serviceName + "_" + version + "_" + environment)) {
            // if no services are present, use the last known service
            log.warning("No instances of " + serviceName + " found, using last known service.");
            presentServices = Collections.singletonMap("lastKnownService", this.lastKnownServices
                    .get(serviceName + "_" + version + "_" + environment));
        }

        List<URL> instances = new LinkedList<>();

        if (presentServices != null && presentServices.size() > 0) {
            URL gatewayUrl = getGatewayUrl(serviceName, version, environment);
            if (accessType == AccessType.GATEWAY && gatewayUrl != null) {
                instances.add(gatewayUrl);
            } else {
                for (Etcd2Service service : presentServices.values()) {
                    if (this.clusterId != null && this.clusterId.equals(service.getClusterId())) {
                        instances.add(service.getContainerUrl());
                    } else {
                        instances.add(service.getBaseUrl());
                    }
                }
            }
        }
        return Optional.of(instances);
    }

    private URL getGatewayUrl(String serviceName, String version, String environment) {
        if (!this.gatewayUrls.containsKey(serviceName + "_" + version + "_" + environment)) {
            URL gatewayUrl = null;

            long index = 0;
            try {
                EtcdKeysResponse etcdKeysResponse = etcd.get(getGatewayKey(environment, serviceName, version))
                        .setRetryPolicy(this.initialRequestRetryPolicy).send().get();
                index = etcdKeysResponse.getNode().getModifiedIndex();

                gatewayUrl = new URL(etcdKeysResponse.getNode().getValue());
            } catch (SocketException | TimeoutException e) {
                String message = "Timeout exception. Cannot read given key in specified time or retry-count " +
                        "constraints.";
                if (resilience) {
                    log.warning(message + " Error: " + e);
                } else {
                    throw new EtcdNotAvailableException(message, e);
                }
            } catch (MalformedURLException e) {
                log.severe("Malformed URL exception: " + e.toString());
            } catch (IOException e) {
                log.info("IO Exception. Cannot read given key: " + e);
            } catch (EtcdException e) {
                // ignore key not found exception
                if (e.getErrorCode() != 100) {
                    log.info("Etcd exception. " + e);
                }
            } catch (EtcdAuthenticationException e) {
                log.severe("Etcd authentication exception. Cannot read given key: " + e);
            }

            this.gatewayUrls.put(serviceName + "_" + version + "_" + environment, gatewayUrl);
            watchServiceInstances(getGatewayKey(environment, serviceName, version), index);

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
    public Optional<URL> getServiceInstance(String serviceName, String version, String environment) {

        return getServiceInstance(serviceName, version, environment, AccessType.DIRECT);

    }

    @Override
    public Optional<List<String>> getServiceVersions(String serviceName, String environment) {
        if (!this.serviceVersions.containsKey(serviceName + "_" + environment)) {
            EtcdKeysResponse etcdKeysResponse = Etcd2Utils.getEtcdDir(etcd, getServiceKeyVersions(environment,
                    serviceName), this.initialRequestRetryPolicy, this.resilience);

            if (etcdKeysResponse != null) {

                List<String> versions = new LinkedList<>();
                for (EtcdKeysResponse.EtcdNode versionNode : etcdKeysResponse.getNode().getNodes()) {

                    String version = Etcd2Utils.getLastKeyLayer(versionNode.getKey());

                    EtcdKeysResponse.EtcdNode instanceParentNode = null;
                    for (EtcdKeysResponse.EtcdNode instanceParentNodeCandidate : versionNode.getNodes()) {
                        if (Etcd2Utils.getLastKeyLayer(instanceParentNodeCandidate.key).equals("instances")) {
                            instanceParentNode = instanceParentNodeCandidate;
                            break;
                        }
                    }
                    if (instanceParentNode == null) {
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
            }
        }

        List<String> presentVersions = this.serviceVersions.get(serviceName + "_" + environment);

        String lastKnownVersion = lastKnownVersions.get(serviceName + "_" + environment);
        if (lastKnownVersion != null && (presentVersions == null || !presentVersions.contains(lastKnownVersion))) {
            // if present versions does not contain version of last known service, add it to the return object

            // make a copy of presentVersions
            if (presentVersions != null) {
                presentVersions = new LinkedList<>(presentVersions);
            } else {
                presentVersions = new LinkedList<>();
            }
            presentVersions.add(lastKnownVersion);
        }

        return Optional.ofNullable(presentVersions);
    }

    @Override
    public void disableServiceInstance(String serviceName, String version, String
            environment, URL url) {

        String key = Etcd2Utils.getServiceKeyInstances(environment, serviceName, version);

        EtcdKeysResponse etcdKeysResponse = Etcd2Utils.getEtcdDir(etcd, key, this.resilience);
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
                    if (((EtcdException) t).isErrorCode(EtcdErrorCode.EventIndexCleared)) {
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
                            if (this.serviceInstances.get(serviceName + "_" + version + "_" + environment)
                                    .size() == 1) {
                                // if removing last service, save it to separate buffer
                                // this service will be returned, if no other services are present
                                this.lastKnownServices.put(serviceName + "_" + version + "_" + environment,
                                        this.serviceInstances.get(serviceName + "_" + version + "_" + environment)
                                                .get(node.getKey()));
                                this.lastKnownVersions.put(serviceName + "_" + environment, version);
                            }
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
                                log.severe("Malformed URL exception: " + e.toString());
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
                                log.severe("Malformed URL exception: " + e.toString());
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
                                log.severe("Malformed URL exception: " + e.toString());
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
                        if (this.serviceInstances.get(serviceName + "_" + version + "_" + environment).size() == 1) {
                            // if removing last service, save it to separate buffer
                            // this service will be returned, if no other services are present
                            this.lastKnownServices.put(serviceName + "_" + version + "_" + environment,
                                    this.serviceInstances.get(serviceName + "_" + version + "_" + environment)
                                            .get(node.getKey() + "/url"));
                            this.lastKnownVersions.put(serviceName + "_" + environment, version);
                        }
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
            } catch (SocketException | TimeoutException e) {
                String message = "Timeout exception. Cannot read given key in specified time or retry-count " +
                        "constraints.";
                if (resilience) {
                    log.warning(message + " Error: " + e);
                } else {
                    throw new EtcdNotAvailableException(message, e);
                }
            } catch (IOException e) {
                log.info("IO Exception. Cannot put given key: " + e);
            } catch (EtcdException e) {
                log.info("Etcd exception. " + e);
            } catch (EtcdAuthenticationException e) {
                log.severe("Etcd authentication exception. Cannot put given key: " + e);
            }

        } else {
            log.severe("etcd not initialised.");
        }

    }
}
