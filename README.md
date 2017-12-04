# KumuluzEE Discovery
[![Build Status](https://img.shields.io/travis/kumuluz/kumuluzee-discovery/master.svg?style=flat)](https://travis-ci.org/kumuluz/kumuluzee-discovery)

> Service discovery extension for the KumuluzEE microservice framework. Service registration, service discovery and client side load balancing with full support for Docker and Kubernetes cluster.

KumuluzEE Discovery is a service discovery extension for the KumuluzEE microservice framework. It provides support 
for service registration, service discovery and client side load balancing.

KumuluzEE Discovery provides full support for microservices packed as Docker containers. It also provides full support 
for executing microservices in clusters and cloud-native platforms with full support for Kubernetes.
 
KumuluzEE Discovery has been designed to support modularity with pluggable service discovery frameworks. Currently, 
etcd and Consul are supported. In the future, other discovery frameworks will be supported too (contributions are welcome).

The extension supports KumuluzEE version 2.4.0 or higher.

## Usage

You can enable etcd-based service discovery by adding the following dependency:
```xml
<dependency>
    <groupId>com.kumuluz.ee.discovery</groupId>
    <artifactId>kumuluzee-discovery-etcd</artifactId>
    <version>${kumuluzee-discovery.version}</version>
</dependency>
```

You can enable Consul-based service discovery by adding the following dependency:
```xml
<dependency>
    <groupId>com.kumuluz.ee.discovery</groupId>
    <artifactId>kumuluzee-discovery-consul</artifactId>
    <version>${kumuluzee-discovery.version}</version>
</dependency>
```

### Configuring etcd 

Etcd can be configured with the common KumuluzEE configuration framework. Configuration properties can be defined with
the environment variables or in the configuration file. For more details see the 
[KumuluzEE configuration wiki page](https://github.com/kumuluz/kumuluzee/wiki/Configuration).

To enable service registration using etcd, an odd number of etcd hosts should be specified with the configuration key 
`kumuluzee.config.etcd.hosts` in the following format
`'http://192.168.99.100:2379,http://192.168.99.101:2379,http://192.168.99.102:2379'`.

In etcd key-value store, services are registered following this schema:
- key: `/environments/'environment'/services/'serviceName'/'serviceVersion'/instances/'automaticallyGeneratedInstanceId'/url`, 
e.g. `/environments/dev/services/my-service/v0.01/instances/1491983746019/url`
- value: service URL, e.g `http://localhost:8080`

**Security**

Etcd can be configured to support user authentication and client-to-server transport security with HTTPS. To access 
authentication-enabled etcd host, username and password have to be defined with configuration keys 
`kumuluzee.config.etcd.username` and `kumuluzee.config.etcd.password`. To enable transport security, follow 
https://coreos.com/etcd/docs/latest/op-guide/security.html 
To access HTTPS-enabled etcd host, PEM certificate string has to be defined with the configuration key `kumuluzee.config.etcd.ca`.

Example of YAML configuration:

```yaml
kumuluzee:
  name: my-service
  env:
    name: test
  version: 1.2.3
  server:
    http:
      port: 8081
    base-url: http://localhost:8081
  discovery:
    etcd:
      hosts: http://127.0.0.1:2379
    ttl: 30
    ping-interval: 5
```

### Configuring Consul

Consul is also configured with the common KumuluzEE configuration framework, similarly as etcd.

By default, Consul connects to the local agent (`http://localhost:8500`) without additional configuration. You can 
specify the URL of the Consul agent with configuration key `kumuluzee.discovery.consul.agent`. Note that Consul is 
responsible for assigning the IP addresses to the registered services and will assign them the IP on which the agent is 
accessible. Specifying an agent IP address is therefore useful in specific situations, for example when you are running 
multiple services on single Docker host and want them to connect to the single Consul agent, running on the same Docker 
host. 

If your service is accessible over https, you must specify that with configuration key 
`kumuluzee.discovery.consul.protocol: https`. Otherwise, http protocol is used.

Consul implementation reregisters services in case of errors and sometimes unused services in critical state remain in
Consul. To avoid this, Consul implementation uses Consul parameter `DeregisterCriticalServiceAfter` when registering
services. To read more about this parameter, see Consul documentation: https://www.consul.io/api/agent/check.html#deregistercriticalserviceafter.
To alter the value of this parameter, set configuration key `kumuluzee.config.consul.deregister-critical-service-after-s`
appropriately. Default value is 60 (1 min).

Services in Consul are registered with the following name: `'environment'/'serviceName'`

Version is stored in service tag with following format: `version='version'`

If the service uses https protocol, tag `https` is added.

### Retry delays

Etcd and Consul implementations support retry delays on watch connection errors. Since they use increasing exponential
delay, two parameters need to be specified:

- `kumuluzee.discovery.start-retry-delay-ms` - Sets the retry delay duration in ms on first error. Default value: 500
- `kumuluzee.discovery.max-retry-delay-ms` - Sets the maximum delay duration in ms on consecutive errors -
  Default value: 900000 (15 min)

Etcd implementation additionally supports fine-grained retry configuration by using the following configuration keys:
- `kumuluzee.discovery.etcd.initial-retry-count` - Specifies how many retries are executed when performing the first
  request on etcd. This mainly affects the time before the first performed discovery returns no instances if the etcd
  is down. Default value: 1.
- `kumuluzee.discovery.resilience` - If `false`, retries are not executed on any etcd request and 
  `EtcdNotAvailableException` is thrown on timeouts. If `true`, retries are executed as configured and timeouts are
  logged, but no exceptions are thrown. Default value: `true`.

### Service registration

Automatic service registration is enabled with the annotation `@RegisterService` on the REST application class (that extends 
`javax.ws.rs.core.Application`). The annotation takes six parameters:

- value: service name. Default value is fully classified class name. Service name can be overridden with configuration key `kumuluzee.name`.
- ttl: time to live of a registration key in the store. Default value is 30 seconds. TTL can be overridden with configuration key `kumuluzee.discovery.ttl`.
- pingInterval: an interval in which service updates registration key value in the store. Default value is 20. Ping interval can be overridden with configuration key `kumuluzee.discovery.ping-interval`.
- environment: environment in which service is registered. Default value is "dev". Environment can be overridden with configuration key `kumuluzee.env.name`.
- version: version of service to be registered. Default value is "1.0.0". Version can be overridden with configuration key `kumuluzee.version`.
- singleton: if true ensures, that only one instance of service with the same name, version and environment is
registered. Default value is false.

Examples of service registration:
```java
@RegisterService(value = "my-service", ttl = 20, pingInterval = 15, environment = "test", version = "1.0.0", singleton = false)
@ApplicationPath("/v1")
public class RestApplication extends Application {
}
```

```java
@RegisterService
@ApplicationPath("/v1")
public class RestApplication extends Application {
}
```

To register a service with etcd, service URL has to be provided with the configuration key `kumuluzee.server.base-url` in 
the following format:`http://localhost:8080`. Consul implementation uses agent's IP address for the URL of registered 
services, so this key is not used.

KumuluzEE Discovery supports registration of multiple different versions of a service in different environments. The 
environment can be set with the configuration key `kumuluzee.env.name`, the default value is `dev`. Service version can 
also be set with the configuration key `kumuluzee.version`, the default value is `1.0.0`. Configuration keys will 
override annotation values.

### Service discovery

Service discovery is implemented by injecting fields with the annotation `@DiscoverService`, which takes four parameters:

- value: name of the service we want to inject.
- environment: service environment, e.g. prod, dev, test. If value is not provided, environment is set to the value 
defined with the configuration key `kumuluzee.env.name`. If the configuration key is not present, value is set to `dev`.
- version: service version or NPM version range. Default value is "*", which resolves to the highest deployed 
version (see chapter [NPM-like versioning](#npm-versioning)).
- accessType: defines, which URL gets injected. Supported values are `AccessType.GATEWAY` and `AccessType.DIRECT`.
Default is `AccessType.GATEWAY`. See section [Access Types](#access-types) for more information.

Injection is supported for the following field types:

- URL
- String
- WebTarget

Example of service discovery in JAX-RS bean:
```java
@RequestScoped
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TestResource {

    @Inject
    @DiscoverService(value = "my-service", environment = "test", version = "1.0.0")
    private URL url;

    @Inject
    @DiscoverService(value = "my-service", environment = "test", version = "1.0.0")
    private String urlString;

    @Inject
    @DiscoverService(value = "my-service", environment = "test", version = "1.0.0")
    private WebTarget webTarget;

}
```

If the service is not found, injection throws `ServiceNotFoundException`. If this behavior is not desired, injection
into `Optional` types can be used:

```java
@RequestScoped
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TestResource {

    @Inject
    @DiscoverService(value = "my-service", environment = "test", version = "1.0.0")
    private Optional<URL> url;

    @Inject
    @DiscoverService(value = "my-service", environment = "test", version = "1.0.0")
    private Optional<String> urlString;

    @Inject
    @DiscoverService(value = "my-service", environment = "test", version = "1.0.0")
    private Optional<WebTarget> webTarget;

}
```

**<a name="access-types"></a>Access Types**

Service discovery supports two access types:
- `AccessType.GATEWAY` returns gateway URL, if it is present. If not, behavior is the same as with `AccessType.DIRECT`.
- `AccessType.DIRECT` always returns base URL or container URL.

If etcd implementation is used, gateway URL is read from etcd key-value store used for service discovery. It is stored
in key `/environments/'environment'/services/'serviceName'/'serviceVersion'/gatewayUrl` and is automatically updated, if 
value changes.

If Consul implementation is used, gateway URL is read from Consul key-value store. It is stored in key
`/environments/'environment'/services/'serviceName'/'serviceVersion'/gatewayUrl` and is automatically updated on
changes, similar as in etcd implementation.

**<a name="npm-versioning"></a>NPM-like versioning**

Service discovery support NPM-like versioning. If service is registered with version in NPM format, it can be 
discovered using a NPM range. Some examples:

- "*" would discover the latest version in NPM format, registered with etcd
- "^1.0.4" would discover the latest minor version in NPM format, registered with etcd
- "~1.0.4" would discover the latest patch version in NPM format, registered with etcd


For more information see [NPM semver documentation](http://docs.npmjs.com/misc/semver).

### Using the last-known service

Etcd implementation improves resilience by saving the information of the last present service, before it gets deleted.
This means, that etcd discovery extension will return the URL of the last-known service, if no services are present in
the registry. When discovering the last-known service a warning is logged.

### Executing service discovery only when needed

When injecting a service using the `@DiscoverService` annotation, the service is discovered every time the bean is
created, even if we call a method that does not require injected service. This usually does not present a problem,
since blocking request to the registry is needed only when the first discovery of the service is performed.
Every following discovery of the service is performed on the internal buffer, which gets updated in the background.

If this still presents a problem, you can avoid service discovery when not needed by either injecting `DiscoveryUtil`
and performing service discovery programmatically, or by using the CDI Provider mechanism as shown below:

```java
@RequestScoped
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DiscoverResources extends BaseResource {

    @Inject
    @DiscoverService(value = "my-service", environment = "dev", version = "1.0.0")
    Provider<Optional<WebTarget>> targetProvider;

    @GET
    @Path("discovery")
    @Produces(MediaType.TEXT_PLAIN)
    public Response discoveryInMethod() {
        Optional<WebTarget> target = targetProvider.get();
        if (target.isPresent()) {
            return Response.ok(target.get().getUri().toString()).build();
        } else {
            return Response.noContent().build();
        }
    }

    @GET
    @Path("nonDiscovery")
    public Response nonDiscoveryMethod() {
        return Response.ok("{}").build();
    }
}
```

### Cluster, cloud-native platforms and Kubernetes

KumuluzEE Discovery is fully compatible with clusters and cloud-native platforms. It has been extensively tested with Kubernetes.
If you are running your services in cluster (for example Kubernetes), you should specify the cluster id in the
configuration key `kumuluzee.discovery.cluster`. Cluster id should be the same for every service running in the same
cluster.

Services running in the same cluster will be discovered by their container IP. Services accessing your service from
outside the cluster will discover your service by its base url (`kumuluzee.server.base-url`).

Container IP is automatically acquired when you run the service. If you want to override it, you can do so by 
specifying configuration key `kumuluzee.container-url`.

## Changelog

Recent changes can be viewed on Github on the [Releases Page](https://github.com/kumuluz/kumuluzee/releases)

## Contribute

See the [contributing docs](https://github.com/kumuluz/kumuluzee-discovery/blob/master/CONTRIBUTING.md)

When submitting an issue, please follow the 
[guidelines](https://github.com/kumuluz/kumuluzee-discovery/blob/master/CONTRIBUTING.md#bugs).

When submitting a bugfix, write a test that exposes the bug and fails before applying your fix. Submit the test alongside the fix.

When submitting a new feature, add tests that cover the feature.

## License

MIT
