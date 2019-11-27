/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.prometheus;

import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.ReadsAttributes;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnShutdown;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Tags({"Prometheus"})
@CapabilityDescription("Uses Prometheus service discovery and metric scraping libraries to extract metrics from running services. Metrics are output in json format")
@SeeAlso({})
@ReadsAttributes({@ReadsAttribute(attribute="", description="")})
@WritesAttributes({@WritesAttribute(attribute="", description="")})
public class GetPrometheusMetrics extends AbstractProcessor {

    public static native int Run(String config, String ID, BlockingQueue<String> metricQueue, BlockingQueue<String> logQueue);
    public static native boolean Stop(String ID);

    public static final PropertyDescriptor PROMETHEUS_CONFIG = new PropertyDescriptor
            .Builder().name("PROMETHEUS_CONFIG")
            .displayName("Prometheus Configuration")
            .description("Prometheus Configuration")
            .required(true)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .build();

    public static final PropertyDescriptor MAX_BATCH_SIZE = new PropertyDescriptor
            .Builder().name("MAX_BATCH_SIZE")
            .displayName("Maximum Batch Size")
            .description("Maximum metrics per FlowFile")
            .defaultValue("10000")
            .required(true)
            .addValidator(StandardValidators.createLongValidator(1,Long.MAX_VALUE,true))
            .build();

    public static final PropertyDescriptor EXTRACT_NAMESPACE = new PropertyDescriptor
            .Builder().name("EXTRACT_NAMESPACE")
            .displayName("Extract namespace")
            .description("Whether to extract the namespace from the name field (e.g. __name__=myNamespace_my_metric_name -> __namespace__=myNamespace, __name__=my_metric_name)")
            .required(true)
            .addValidator(StandardValidators.BOOLEAN_VALIDATOR)
            .allowableValues("true", "false")
            .defaultValue("true")
            .build();

    public static final Relationship METRICS = new Relationship.Builder()
            .name("METRICS")
            .description("Metrics")
            .build();

    private List<PropertyDescriptor> descriptors;

    private Set<Relationship> relationships;

    private ArrayBlockingQueue<String> metricQueue;
    private ArrayBlockingQueue<String> logQueue;
    private boolean initialized = false;
    private long max_batch_size = 1;
    private boolean extract_namespace = true;

    private String id = java.util.UUID.randomUUID().toString();

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descriptors = new ArrayList<PropertyDescriptor>();
        descriptors.add(PROMETHEUS_CONFIG);
        descriptors.add(MAX_BATCH_SIZE);
        descriptors.add(EXTRACT_NAMESPACE);
        this.descriptors = Collections.unmodifiableList(descriptors);

        final Set<Relationship> relationships = new HashSet<Relationship>();
        relationships.add(METRICS);
        this.relationships = Collections.unmodifiableSet(relationships);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {

        if (!initialized) {
            initialized=true;
            System.loadLibrary("prommetricsapi");
            this.metricQueue = new ArrayBlockingQueue<>(10000);
            this.logQueue = new ArrayBlockingQueue<>(10000);

            max_batch_size = context.getProperty(MAX_BATCH_SIZE).asLong();
            extract_namespace = context.getProperty(EXTRACT_NAMESPACE).asBoolean();

            new Thread(() -> {

                getLogger().info("Starting Discovery and Scrapping");
                Run(context.getProperty(PROMETHEUS_CONFIG).getValue(), id, metricQueue, logQueue);
                getLogger().info("Discovery and Scrapping Stopped");
            }).start();
        }


        String log = logQueue.poll();
        while(log != null){
            if(log.contains("level=info"))
                getLogger().info(log);
            else if(log.contains("level=debug"))
                getLogger().debug(log);
            else
                getLogger().error(log);

            log = logQueue.poll();
        }

        StringBuilder metrics = null;
        int count = 0;
        String metric = metricQueue.poll();
        while(metric != null && count < max_batch_size ) {
            if(metrics == null)
                metrics = new StringBuilder();
            else
                metrics.append("\n");

            metrics.append(convertToJSON(metric, extract_namespace));
            count++;

            metric = metricQueue.poll();
        }
        if(metrics != null){
            final byte[] metricBytes = metrics.toString().getBytes(StandardCharsets.UTF_8);
            FlowFile result = session.create();
            result = session.write(result, out -> out.write(metricBytes));
            session.transfer(result, METRICS);
        }
    }
    @OnStopped @OnShutdown
    public void stopped(){
        Stop(this.id);
        this.initialized = false;
    }

    private static final Pattern namePattern = Pattern.compile(".*__name__=\"([^\"]*)\",.*");
    public static String convertToJSON(String promMetric, boolean extract_namespace){
        //{__name__="go_gc_duration_seconds", instance="localhost:9100", job="prometheus", quantile="0"} 1563377239705 0.000000


        //Parse out namespace
        //__name__="namespace_met_ric",
        //__namespace__="namespace", __name__="met_ric",
        if(extract_namespace) {
            Matcher m = namePattern.matcher(promMetric);
            if (m.find()) {
                String name = m.group(1);
                int index = name.indexOf('_');
                if (index != -1) {
                    String namespace = name.substring(0, index);
                    String shortName = name.substring(index + 1);
                    promMetric = promMetric.replaceAll(
                            "__name__=\"[^\"]*\",",
                            "__namespace__=\\\"" + namespace + "\\\", __name__=\"" + shortName + "\","
                    );
                }
            }
        }

        //Convert to valid JSON
        //find: } # #
        //replace: , ts=#, value=#}
        return promMetric
                .replaceAll("} ([0-9.]*) ([-0-9.]*)$", ", ts=$1, value=$2}")
                .replaceAll("\\{([a-zA-Z_\\-]*)=","{\"$1\":")
                .replaceAll(", ([a-zA-Z_\\-]*)=",", \"$1\":");
    }
}
