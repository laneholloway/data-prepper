# Core Data Prepper APIs

All Data Prepper instances expose a server with some control APIs. By default, this server runs
on port 4900. Some plugins, especially Source plugins may expose other servers. These will be
on different ports and their configurations are independent of the core API.

For example, to shut down Data Prepper, you can run:

```
curl -X POST http://localhost:4900/shutdown
```

## APIs

The following APIs are available:

* /list
    * lists running pipelines
* /shutdown
    * starts a graceful shutdown of the Data Prepper
* /metrics/prometheus
    * returns a scrape of the Data Prepper metrics in Prometheus text format. This API is available provided
      `metricsRegistries` parameter in data prepper configuration file `data-prepper-config.yaml` has `Prometheus` as one
      of the registry
* /metrics/sys
    * returns JVM metrics in Prometheus text format. This API is available provided `metricsRegistries` parameter in data
      prepper configuration file `data-prepper-config.yaml` has `Prometheus` as one of the registry

## Configuring the Server

You can configure your Data Prepper core APIs through the `data-prepper-config.yaml` file. 

Many of the Getting Started guides in this project disable SSL on the endpoint.

```
ssl: false
```

To enable SSL on your Data Prepper endpoint, configure your `data-prepper-config.yaml`
with the following:

```
ssl: true
keyStoreFilePath: "/usr/share/data-prepper/keystore.jks"
keyStorePassword: "secret"
privateKeyPassword: "secret"
```
