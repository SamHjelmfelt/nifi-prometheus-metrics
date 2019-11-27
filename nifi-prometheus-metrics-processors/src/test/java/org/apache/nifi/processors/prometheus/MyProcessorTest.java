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

import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


public class MyProcessorTest {

    TestRunner testRunner;

    @Before
    public void init() {
        testRunner = TestRunners.newTestRunner(GetPrometheusMetrics.class);
    }

    @Test
    public void testProcessor() {

        String config =
                "scrape_configs: \n"+
                "- job_name: 'prometheus'\n"+
                "  scrape_interval:  5s\n"+
                "  static_configs: \n"+
                "  - targets: [\"localhost:9100\"] \n";

        testRunner.setProperty(GetPrometheusMetrics.PROMETHEUS_CONFIG, config);
        testRunner.setProperty(GetPrometheusMetrics.MAX_BATCH_SIZE, "100");

        List<MockFlowFile> results = null;


        while (results == null || results.size() == 0){
            try {
                testRunner.run(1,false, false);
                System.out.println("Sleeping...");
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            results = testRunner.getFlowFilesForRelationship(GetPrometheusMetrics.METRICS);
        }
        System.out.println("Metric count: " + results.size());
        MockFlowFile result = results.get(0);
        String resultValue = new String(testRunner.getContentAsByteArray(result));

        System.out.println("Done: " + resultValue);
    }
    @Test
    public void testJSONConversion(){
        String input = "{__name__=\"go_gc_duration_seconds\", instance=\"localhost:9100\", job=\"prometheus\", quantile=\"0\"} 1563377239705 -0.001332";
        String expectedOutput = "{\"__namespace__\":\"go\", \"__name__\":\"gc_duration_seconds\", \"instance\":\"localhost:9100\", \"job\":\"prometheus\", \"quantile\":\"0\", \"ts\":1563377239705, \"value\":-0.001332}";
        String actualOutput = GetPrometheusMetrics.convertToJSON(input, true);
        assertEquals(expectedOutput, actualOutput);
    }
    @Test
    public void testJSONConversion2(){
        String input = "{__name__=\"node_timex_offset_seconds\", instance=\"localhost:9100\", job=\"system\"} 1567618582586 -0.001332";
        String expectedOutput = "{\"__name__\":\"node_timex_offset_seconds\", \"instance\":\"localhost:9100\", \"job\":\"system\", \"ts\":1567618582586, \"value\":-0.001332}";
        String actualOutput = GetPrometheusMetrics.convertToJSON(input, false);
        assertEquals(expectedOutput, actualOutput);
    }

}