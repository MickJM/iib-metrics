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

Create a queue;

```
define ql(IIB.METRICS)
```

Define the topic to which IIB will publish to;

```
define topic(IIB.METRICS) topicstr('$SYS/Broker/+/ResourceStatsistics') descr('IIB Metrics)
```

Define a subscription to put the messages onto the queue;

```
define sub(IIB.METRICS.ALL) topobj(IIB.METRICS) topicstr('#') dest('{queue name}')
```
