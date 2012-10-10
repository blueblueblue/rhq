/*
 *
 *  * RHQ Management Platform
 *  * Copyright (C) 2005-2012 Red Hat, Inc.
 *  * All rights reserved.
 *  *
 *  * This program is free software; you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License, version 2, as
 *  * published by the Free Software Foundation, and/or the GNU Lesser
 *  * General Public License, version 2.1, also as published by the Free
 *  * Software Foundation.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  * GNU General Public License and the GNU Lesser General Public License
 *  * for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * and the GNU Lesser General Public License along with this program;
 *  * if not, write to the Free Software Foundation, Inc.,
 *  * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 *
 */

package org.rhq.enterprise.server.cassandra;

import static org.rhq.core.domain.cloud.Server.OperationMode.MAINTENANCE;
import static org.rhq.core.domain.cloud.Server.OperationMode.NORMAL;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransportException;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import org.rhq.core.domain.cloud.Server;
import org.rhq.enterprise.server.cloud.CloudManagerLocal;
import org.rhq.enterprise.server.cloud.instance.ServerManagerLocal;
import org.rhq.enterprise.server.util.LookupUtil;

import me.prettyprint.cassandra.service.CassandraHost;

/**
 * @author John Sanda
 */
public class CassandraClusterHeartBeatJob implements Job {

    public static final String JOB_NAME = CassandraClusterHeartBeatJob.class.getSimpleName();
    public static final String KEY_CONNECTION_TIMEOUT = "rhq.cassandra.connection.timeout";
    public static final String KEY_CASSANDRA_HOSTS = "rhq.cassandra.hosts";

    private final Log log = LogFactory.getLog(CassandraClusterHeartBeatJob.class);

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Server rhqServer = getRhqServer();
        JobDataMap dataMap = context.getMergedJobDataMap();
        String hosts = (String) dataMap.get(KEY_CASSANDRA_HOSTS);
        int timeout =  Integer.parseInt((String) dataMap.get(KEY_CONNECTION_TIMEOUT));

        for (String s : hosts.split(",")) {
            String[] params = s.split(":");
            CassandraHost host = new CassandraHost(params[0], Integer.parseInt(params[1]));
            TSocket socket = new TSocket(host.getHost(), host.getPort(), timeout);
            try {
                socket.open();
                if (log.isDebugEnabled()) {
                    log.debug("Successfully connected to cassandra node [" + host + "]");
                }
                if (rhqServer.getOperationMode() != NORMAL) {
                    changeServerMode(rhqServer, NORMAL);
                }
                return;
            } catch (TTransportException e) {
                String msg = "Unable to open thrift connection to cassandra node [" + host + "]";
                logException(msg, e);
            }
        }
        if (log.isWarnEnabled()) {
            log.warn(rhqServer + " is unable to connect to any Cassandra node. Server will go into maintenance mode.");
        }
        changeServerMode(rhqServer, MAINTENANCE);
    }

    private Server getRhqServer() {
        ServerManagerLocal serverManager = LookupUtil.getServerManager();
        return serverManager.getServer();
    }

    private void changeServerMode(Server rhqServer, Server.OperationMode mode) {
        if (rhqServer.getOperationMode() == mode) {
            return;
        }

        if (log.isInfoEnabled()) {
            log.info("Moving " + rhqServer + " from " + rhqServer.getOperationMode() + " to " + mode);
        }
        CloudManagerLocal rhqClusterManager = LookupUtil.getCloudManager();
        rhqClusterManager.updateServerMode(new Integer[] {rhqServer.getId()}, mode);
    }

    private void logException(String msg, Exception e) {
        if (log.isDebugEnabled()) {
            log.debug(msg, e);
        } else if (log.isInfoEnabled()) {
            log.info(msg + ": " + e.getMessage());
        } else {
            log.warn(msg);
        }
    }
}