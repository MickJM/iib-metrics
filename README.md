# iib-metrics (IIB Resource Metrics)

## Pre-requisits

### IIB Node

For the iib-metrics API to run, there needs to be an MQ server that is connected to an IIB node;

To enable IIB resource metrics on the IIB run-time broker;

```
mqsichangeresourcestats {broker name} -c active
```

To disable IIB resource metrics on the IIB run-time broker;

```
mqsichangeresourcestats {broker name} -c inactive
```

### IBM MQ Server

On the server connected to the IIB run-time;

