# NiFi Prometheus Metrics
NiFi Processor that performs Prometheus service discovery and metric scraping

## Usage
All Prometheus service discovery and scrape configurations should be supported.   
Prometheus configurations: https://prometheus.io/docs/prometheus/latest/configuration/configuration/   
See also: https://prometheus.io/docs/instrumenting/exporters/

Metrics are output in JSON format with one metric per flowfile. For example:   
{"__name__"="go_gc_duration_seconds", "instance"="localhost:9100", "job"="prometheus", "quantile"="0", "ts"=1563377239705, "value"=0.000000}

Optionally based on configuration, the processor can extract the namespace into its own field:
For example: __name__=myNamespace_my_metric_name -> __namespace__=myNamespace, __name__=my_metric_name


Note that this processor uses a native library, so it will need to be built for each target architecture. The release zip contains native libraries for both centos and mac.


## How to Build
```
#Prerequisites (in PATH): git, go, maven, gcc
#Environment Variables: JAVA_HOME
mvn clean install
```

## How to Install (HDF)
```
cp NiFi-Prometheus-Metrics/nifi-prometheus-metrics-nar/target/nifi-prometheus-metrics-nar-1.0-SNAPSHOT.nar /usr/hdf/current/nifi/lib/

#If mac
#cp NiFi-Prometheus-Metrics/nifi-prometheus-metrics-processors/target/libprommetricsapi.jnilib /usr/hdf/current/nifi/lib/

#Else if Linux
cp NiFi-Prometheus-Metrics/nifi-prometheus-metrics-processors/target/libprommetricsapi.so /usr/hdf/current/nifi/lib/

#Add to nifi-env.sh (in Ambari, Advanced nifi-env -> Template for nifi-env.sh)
#export LD_LIBRARY_PATH=/usr/hdf/current/nifi/lib/
```

## How It Works
CGO is used to compile the relevant Prometheus classes and a small wrapper into a shared object (.so) file that can be loaded into the JVM using JNI. The Java code instantiates a blocking queue and passes it as a parameter into the native libary along with the Prometheus configuration. The native library starts the service discovery and metric scraping and puts the metrics that it scrapes into this queue for the Java code to consume. The Java code converts the metrics to JSON and outputs them as NiFi FlowFiles.
