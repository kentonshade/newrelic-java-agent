/*
 *
 *  * Copyright 2020 New Relic Corporation. All rights reserved.
 *  * SPDX-License-Identifier: Apache-2.0
 *
 */

package com.newrelic.agent.jfr;

import com.newrelic.agent.Agent;
import com.newrelic.agent.config.AgentConfig;
import com.newrelic.agent.config.JfrConfig;
import com.newrelic.agent.service.AbstractService;
import com.newrelic.agent.service.ServiceFactory;
import com.newrelic.jfr.daemon.*;
import com.newrelic.telemetry.Attributes;

import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

import static com.newrelic.jfr.daemon.AttributeNames.ENTITY_GUID;
import static com.newrelic.jfr.daemon.AttributeNames.SERVICE_NAME;
import static com.newrelic.jfr.daemon.SetupUtils.buildCommonAttributes;
import static com.newrelic.jfr.daemon.SetupUtils.buildUploader;

public class JfrService extends AbstractService {

    private final JfrConfig jfrConfig;
    private final AgentConfig defaultAgentConfig = ServiceFactory.getConfigService().getDefaultAgentConfig();

    public JfrService(JfrConfig jfrConfig) {
        super(JfrService.class.getSimpleName());
        this.jfrConfig = jfrConfig;
    }

    @Override
    protected void doStart() {

        if (coreApisExist() && isEnabled()) {
            Agent.LOG.log(Level.INFO, "Attaching New Relic JFR Monitor");

            try {
                DaemonConfig daemonConfig = buildDaemonConfig();
                final Attributes commonAttrs = buildCommonAttributes();
                final String entityGuid = ServiceFactory.getRPMService().getEntityGuid();
                Agent.LOG.log(Level.INFO, "JFR Monitor obtained entity guid from agent: " + entityGuid);
                commonAttrs.put(ENTITY_GUID, entityGuid);
                commonAttrs.put(SERVICE_NAME, daemonConfig.getMonitoredAppName());

                JFRUploader uploader = buildUploader(daemonConfig);
                uploader.readyToSend(new EventConverter(commonAttrs));
                final JfrController jfrController = SetupUtils.buildJfrController(daemonConfig, uploader);

                ExecutorService jfrMonitorService = Executors.newSingleThreadExecutor();
                jfrMonitorService.submit(
                        new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    jfrController.loop();
                                } catch (JfrRecorderException e) {
                                    Agent.LOG.log(Level.INFO, "Error in JFR Monitor, shutting down", e);
                                    jfrController.shutdown();
                                }
                            }
                        });
            } catch (Throwable t) {
                Agent.LOG.log(Level.INFO, "Unable to attach JFR Monitor", t);
            }
        }
    }

    @Override
    public final boolean isEnabled() {
        final boolean enabled = jfrConfig.isEnabled();
        if (!enabled) {
            Agent.LOG.log(Level.INFO, "New Relic JFR Monitor is disabled: JFR config has not been enabled in the Java agent.");
        }
        return enabled;
    }

    @Override
    protected void doStop() {

    }

    private boolean coreApisExist() {
        try {
            Class.forName("jdk.jfr.Recording");
            Class.forName("jdk.jfr.FlightRecorder");
        } catch (ClassNotFoundException __) {
            Agent.LOG.log(Level.WARNING, "Not starting JFR Service. Core JFR APIs do not exist in this JVM.");
            return false;
        }
        return true;
    }

    private DaemonConfig buildDaemonConfig() {
        DaemonConfig.Builder builder = DaemonConfig.builder()
                .daemonVersion(VersionFinder.getVersion())
                .useLicenseKey(jfrConfig.useLicenseKey())
                .apiKey(defaultAgentConfig.getLicenseKey())
                .monitoredAppName(defaultAgentConfig.getApplicationName())
                .auditLogging(jfrConfig.auditLoggingEnabled())
                .metricsUri(URI.create(defaultAgentConfig.getMetricIngestUri()))
                .eventsUri(URI.create(defaultAgentConfig.getEventIngestUri()))
                .proxyHost(defaultAgentConfig.getProxyHost())
                .proxyScheme(defaultAgentConfig.getProxyScheme())
                .proxyPort(defaultAgentConfig.getProxyPort())
                .proxyUser(defaultAgentConfig.getProxyUser())
                .proxyPassword(defaultAgentConfig.getProxyPassword());
        return builder.build();
    }
}
