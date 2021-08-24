/*
 * Copyright 2000-2021 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.base.devserver.stats;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 *  Development more usage statistic storage and methods for loading,
 *  saving and sending the data.
 */
public class StatisticsStorage {

        private String projectId;
        private ObjectNode json;
        private ObjectNode projectJson;
        private boolean usageStatisticsEnabled;
        private String reportingUrl;
        private File usageStatisticsFile;


        /** Singleton pattern */
        private static final AtomicReference<StatisticsStorage> instance = new AtomicReference<>();

        /**
         * Get the instantiated StatisticsStorage.
         *
         * @return Instance of StatisticsStorage.
         */
        public static StatisticsStorage get() {
                if (instance.get() == null) {
                        StatisticsStorage newStats = new StatisticsStorage();
                        instance.compareAndSet(null, newStats);
                }
                return instance.get();
        }

        private StatisticsStorage() {
                // Avoid external creation of instances
        }

        /**
         *  Get the pseudonymized project id.
         *
         * @return
         */
        String getProjectId() {
                return projectId;
        }

        /**
         * Set the project id. All subsequent calls to stores data is stored
         * using this project id.
         *
         * @param projectId
         */
        void setProjectId(String projectId) {
                this.projectId = projectId;
                // Find the project we are working on
                if (!json.has(StatisticsConstants.FIELD_PROJECTS)) {
                        json.set(StatisticsConstants.FIELD_PROJECTS,
                                JsonHelpers.getJsonMapper().createArrayNode());
                }
                this.projectJson = JsonHelpers.findById(this.projectId,
                        this.json.get(StatisticsConstants.FIELD_PROJECTS),
                        StatisticsConstants.FIELD_PROJECT_ID,
                        true);
        }


        /**
         *  Check if statistics are enabled for this project.
         *
         * @return true if statistics collection is enabled.
         */
        boolean isStatisticsEnabled() {
                return this.usageStatisticsEnabled;
        }

        /**
         * Enable or disable statistics collection and sending.
         *
         * @see DevModeUsageStatistics#isStatisticsEnabled()
         * @param enabled true if statistics should be collected, false otherwise.
         */
        void setStatisticsEnabled(boolean enabled) {
                this.usageStatisticsEnabled = enabled;
        }

        /**
         *  Get the remote reporting URL.
         **
         * @return Returns {@link StatisticsConstants#USAGE_REPORT_URL} by default.
         */
        String getUsageReportingUrl() {
                return reportingUrl == null? StatisticsConstants.USAGE_REPORT_URL : reportingUrl;
        }

        /**
         *  Get the remote reporting URL.
         *
         * @return By default return {@link StatisticsConstants#USAGE_REPORT_URL}.
         */
        void setUsageReportingUrl(String reportingUrl) {
                this.reportingUrl = reportingUrl;
        }


        /**
         *  Helper to update client data in current project.
         *
         * @param clientData Json data received from client.
         */
        void updateProjectTelemetryData(JsonNode clientData) {
                try {
                        if (clientData != null && clientData.isObject()) {
                                clientData.fields().forEachRemaining(e -> projectJson.set(e.getKey(), e.getValue()));
                        }
                } catch (Exception e) {
                        getLogger().debug("Failed to update client telemetry data", e);
                }
        }

        /**
         *  Send current statistics to given reporting URL.
         *
         * Reads the current data and posts it to given URL. Updates or replaces
         * the local data according to the response.
         *
         * Updates <code>FIELD_LAST_SENT</code> and <code>FIELD_LAST_STATUS</code>.
         *
         * @see #postData(String, JsonNode)
         */
        String sendCurrentStatistics() {

                // Post copy of the current data
                String message = null;
                JsonNode response = postData(getUsageReportingUrl(),json.deepCopy());

                // Update the last sent time
                // If the last send was successful we clear the project data
                if (response != null && response.isObject() && response.has(
                        StatisticsConstants.FIELD_LAST_STATUS)) {
                        json.put(StatisticsConstants.FIELD_LAST_SENT,System.currentTimeMillis());
                        json.put(StatisticsConstants.FIELD_LAST_STATUS,response.get(
                                StatisticsConstants.FIELD_LAST_STATUS).asText());

                        // Use different interval, if requested in response or default to 24H
                        if (response.has(StatisticsConstants.FIELD_SEND_INTERVAL)
                                && response.get(StatisticsConstants.FIELD_SEND_INTERVAL).isNumber()) {
                                json.put(StatisticsConstants.FIELD_SEND_INTERVAL, normalizeInterval(response.get(
                                        StatisticsConstants.FIELD_SEND_INTERVAL).asLong()));
                        } else {
                                json.put(StatisticsConstants.FIELD_SEND_INTERVAL, StatisticsConstants.TIME_SEC_24H);
                        }

                        // Update the server message
                        if (response.has(StatisticsConstants.FIELD_SERVER_MESSAGE)
                                && response.get(StatisticsConstants.FIELD_SERVER_MESSAGE).isTextual()) {
                                message = response.get(StatisticsConstants.FIELD_SERVER_MESSAGE).asText();
                                json.put(StatisticsConstants.FIELD_SERVER_MESSAGE, message);
                        }

                        // If data was sent ok, clear the existing project data
                        if (response.get(StatisticsConstants.FIELD_LAST_STATUS).asText().startsWith("200:")) {
                                json.set(StatisticsConstants.FIELD_PROJECTS, JsonHelpers.getJsonMapper().createArrayNode());
                                projectJson = JsonHelpers.findById(projectId,
                                        json.get(StatisticsConstants.FIELD_PROJECTS),
                                        StatisticsConstants.FIELD_PROJECT_ID,
                                        true);
                        }
                }

                return message;
        }

        public void setValue(String field, String value) {
                projectJson.put(field, value);
        }

        /**
         * Update a single increment number value in current project data.
         *
         *  Stores the data to the disk automatically.
         *
         * @see JsonHelpers#incrementJsonValue(ObjectNode, String)
         * @param fieldName name of the field to increment
         */
        public void increment(String fieldName) {
                JsonHelpers.incrementJsonValue(projectJson,fieldName);
        }

        /**
         * Get a value of number value in current project data.
         *
         * @see #increment(String) (String)
         * @param fieldName name of the field to get
         * @return Value if this is integer field, -1 if missing
         */
        public int getFieldValue(String fieldName) {
                if (projectJson != null && projectJson.has(fieldName) && projectJson.get(fieldName).isInt()) {
                        return projectJson.get(fieldName).asInt();
                }
                return -1;
        }

        /**
         * Set a global value in storage.
         *
         * @see #increment(String) (String)
         * @param globalField name of the field to get
         * @param value The new value to set
         */
        public void setGlobalValue(String globalField, String value) {
                json.put(globalField,value);
        }

        /**
         * Check the Interval has elapsed.
         *
         * Uses <code>System.currentTimeMillis</code> as time source.
         *
         * @see #getLastSendTime()
         * @see #getInterval()
         * @return true if enough time has passed since the last send attempt.
         */
        public boolean isIntervalElapsed() {
                long now = System.currentTimeMillis();
                long lastSend = getLastSendTime();
                long interval = getInterval();
                return lastSend+interval*1000 < now;
        }

        /**
         * Reads the statistics update interval.
         *
         * @see StatisticsConstants#FIELD_SEND_INTERVAL
         * @return Time interval in seconds. {@link StatisticsConstants#TIME_SEC_24H} in
         * minumun and {@link StatisticsConstants#TIME_SEC_30D} as maximum.
         */
        public long getInterval() {
                try {
                        long interval = json.get(StatisticsConstants.FIELD_SEND_INTERVAL)
                                .asLong();
                        return normalizeInterval(interval);
                } catch (Exception e) {
                        // Just return the default value
                }
                return StatisticsConstants.TIME_SEC_24H;
        }

        /**
         *  Gets the last time the data was collected according to the statistics file.
         *
         * @see StatisticsConstants#FIELD_LAST_SENT
         * @return Unix timestamp or -1 if not present
         */
        public long getLastSendTime() {
                try {
                        return json.get(StatisticsConstants.FIELD_LAST_SENT).asLong();
                } catch (Exception e) {
                        // Use default value in case of any problems
                }
                return -1; //
        }

        /**
         *  Gets the last time the data was collected according to the statistics file.
         *
         * @see StatisticsConstants#FIELD_LAST_STATUS
         * @return Unix timestamp or -1 if not present
         */
        public String getLastSendStatus() {
                try {
                        return json.get(StatisticsConstants.FIELD_LAST_STATUS).asText();
                } catch (Exception e) {
                        // Use default value in case of any problems
                }
                return null; //
        }

        /**
         * Get interval that is between {@link StatisticsConstants#TIME_SEC_12H}
         * and {@link StatisticsConstants#TIME_SEC_30D}
         *
         * @param intervalSec Interval to normalize
         * @return <code>interval</code> if inside valid range.
         */
        private static long normalizeInterval(long intervalSec) {
                if (intervalSec < StatisticsConstants.TIME_SEC_12H) {
                        return StatisticsConstants.TIME_SEC_12H;
                }
                if (intervalSec > StatisticsConstants.TIME_SEC_30D) {
                        return StatisticsConstants.TIME_SEC_30D;
                }
                return intervalSec;
        }

        /** Posts given Json data to a URL.
         *
         * Updates <code>FIELD_LAST_STATUS</code>.
         *
         * @param posrtUrl URL to post data to.
         * @param data Json data to send
         * @return Response or <code>data</code> if the data was not successfully sent.
         */
        private static ObjectNode postData(String posrtUrl, JsonNode data) {
                ObjectNode result;
                try {
                        HttpPost post = new HttpPost(posrtUrl);
                        post.addHeader("Content-Type", "application/json");
                        post.setEntity(new StringEntity(JsonHelpers.getJsonMapper().writeValueAsString(data)));

                        HttpClient client = HttpClientBuilder.create().build();
                        HttpResponse response = client.execute(post);
                        String responseStatus = response.getStatusLine().getStatusCode() + ": " + response.getStatusLine().getReasonPhrase();
                        JsonNode jsonResponse = null;
                        if (response.getStatusLine().getStatusCode()== HttpStatus.SC_OK) {
                                String responseString = EntityUtils.toString(response.getEntity());
                                jsonResponse = JsonHelpers.getJsonMapper().readTree(responseString);
                        }

                        if (jsonResponse != null && jsonResponse.isObject()) {
                                result = (ObjectNode) jsonResponse;
                        } else {
                                // Default response in case of any problems
                                result = JsonHelpers.getJsonMapper().createObjectNode();
                        }
                        // Update the status and return the results
                        result.put(StatisticsConstants.FIELD_LAST_STATUS, responseStatus);
                        return result;

                } catch (IOException e) {
                        getLogger().debug("Failed to send statistics.",e);
                }

                // Fallback
                result = JsonHelpers.getJsonMapper().createObjectNode();
                result.put(StatisticsConstants.FIELD_LAST_STATUS,
                        StatisticsConstants.INVALID_SERVER_RESPONSE);
                return result;
        }

        /**
         *  Read the data from local project statistics file.
         *
         * @see #getUsageStatisticsStore()
         * @return  Json data in the file or empty Json node.
         */
        synchronized void read() {
                File file = getUsageStatisticsStore();
                getLogger().debug("Reading statistics from "+file.getAbsolutePath());
                try {
                        if (file.exists()) {
                                // Read full data and make sure we track the right project
                                json = (ObjectNode)JsonHelpers.getJsonMapper().readTree(file);
                                if (this.projectId != null) {
                                        projectJson = JsonHelpers.findById(this.projectId,
                                                json.get(StatisticsConstants.FIELD_PROJECTS),
                                                StatisticsConstants.FIELD_PROJECT_ID,
                                                true);
                                }
                                return;
                        }
                } catch (JsonProcessingException e) {
                        getLogger().debug("Failed to parse json", e);
                } catch (IOException e) {
                        getLogger().debug("Failed to read json", e);
                }

                // Empty node if nothing else is found and make sure we
                // track the right project
                json = JsonHelpers.getJsonMapper().createObjectNode();
                json.set(StatisticsConstants.FIELD_PROJECTS, JsonHelpers.getJsonMapper().createArrayNode());
                if (this.projectId != null) {
                        projectJson = JsonHelpers.findById(this.projectId,
                                json.get(StatisticsConstants.FIELD_PROJECTS),
                                StatisticsConstants.FIELD_PROJECT_ID,
                                true);
                }
        }

        /**
         *  Writes the data to local project statistics json file.
         *
         * @see #getUsageStatisticsStore()
         */
        public void write() {
                try {
                        JsonHelpers.getJsonMapper().writeValue(getUsageStatisticsStore(), json);
                } catch (IOException e) {
                        getLogger().debug("Failed to write json", e);
                }
        }

        /**
         *
         * Set usage statistics json file location.
         *
         * @return the location of statistics storage file.
         */
        void setUsageStatisticsStore(File usageStatistics) {
                this.usageStatisticsFile = usageStatistics;
        }

        /**
         *
         * Get usage statistics json file location.
         *
         * @see ProjectHelpers#resolveStatisticsStore()
         * @return the location of statistics storage file.
         */
         File getUsageStatisticsStore() {
                if (this.usageStatisticsFile == null) {
                        this.usageStatisticsFile = ProjectHelpers.resolveStatisticsStore();;
                }
                return this.usageStatisticsFile;
        }

        /** Get number of projects.
         *
         * @return Number of projects or zero.
         */
        public int getNumberOfProjects() {
                if (json != null && json.has(StatisticsConstants.FIELD_PROJECTS)) {
                        return json.get(StatisticsConstants.FIELD_PROJECTS).size();
                }
                return 0;
        }

        /**
         *  Get the last server message.
         *
         * @return The message string returned from server in last successful requests.
         */
        public String getLastServerMessage() {
             return json.has(StatisticsConstants.FIELD_SERVER_MESSAGE) ?
                     json.get(StatisticsConstants.FIELD_SERVER_MESSAGE).asText() :
                     null;
        }

        private static Logger getLogger() {
                // Use the same logger that DevModeUsageStatistics uses
                return LoggerFactory.getLogger(DevModeUsageStatistics.class.getName());
        }
}
