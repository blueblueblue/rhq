/*
 * RHQ Management Platform
 * Copyright (C) 2005-2008 Red Hat, Inc.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.rhq.enterprise.server.core.plugin;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.management.ObjectName;
import javax.sql.DataSource;
import javax.transaction.TransactionManager;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.jboss.deployment.scanner.URLDeploymentScanner;
import org.jboss.mx.util.ObjectNameFactory;

import org.rhq.core.domain.plugin.Plugin;
import org.rhq.core.domain.util.MD5Generator;
import org.rhq.core.util.jdbc.JDBCUtil;
import org.rhq.core.util.stream.StreamUtil;
import org.rhq.enterprise.server.util.LookupUtil;

/**
 * This is an extended URL deployment scanner so this can look at both the
 * file system and the database for new agent plugins.
 * 
 * If an agent plugin is different in the database than on the filesystem,
 * this scanner will stream the plugin's content to the filesystem. This
 * scanner will then do the normal file system scanning (and will therefore
 * see the new plugin from the database now in the file system and will
 * process it normally, as if someone hand-copied that plugin to the
 * file system).
 * 
 * @author John Mazzitelli
 */
public class AgentPluginURLDeploymentScanner extends URLDeploymentScanner {

    /**
     * The name of this MBean when deployed.
     */
    public static ObjectName OBJECT_NAME = ObjectNameFactory
        .create("rhq:service=AgentPluginURLDeploymentScanner,type=DeploymentScanner,flavor=URL");

    private Log log = LogFactory.getLog(AgentPluginURLDeploymentScanner.class);

    /** The location where the user stores agent plugin files */
    private File pluginDirectory;

    /**
     * This will first scan the database for new/updated plugins and if it finds
     * any, will write the content as plugin files in the file system. After that,
     * this will tell the superclass to perform the normal file system scanning.
     */
    @Override
    public synchronized void scan() throws Exception {
        scanDatabase();
        super.scan();
        return;
    }

    /**
     * This will check to see if there are any plugin records in the database
     * that do not have content associated with them and if so, will stream
     * the content from the file system to the database. This is needed only
     * in the case when this server has recently been upgraded from an old
     * version of the software that did not originally have content stored in the DB.
     */
    @Override
    public void startService() throws Exception {
        // the first URL in the scanner's list must be the agent plugin directory
        this.pluginDirectory = new File(((URL) getURLList().get(0)).toURI());

        fixMissingPluginContent();

        super.startService();
    }

    /**
     * This method scans the database for any new or updated agent plugins and make sure this server
     * has a plugin file on the filesystem for each of those new/updated agent plugins.
     */
    private void scanDatabase() throws Exception {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        // these are plugins (name/path/md5/mtime) that have changed in the DB but are missing from the file system
        List<Plugin> updatedPlugins = new ArrayList<Plugin>();

        try {
            DataSource ds = LookupUtil.getDataSource();
            conn = ds.getConnection();

            // get all the plugins
            ps = conn.prepareStatement("SELECT NAME, PATH, MD5, MTIME FROM " + Plugin.TABLE_NAME);
            rs = ps.executeQuery();
            while (rs.next()) {
                String name = rs.getString(1);
                String path = rs.getString(2);
                String md5 = rs.getString(3);
                long mtime = rs.getLong(4);
                File file = new File(this.pluginDirectory, path);
                if (file.exists()) {
                    long fileMtime = file.lastModified();
                    if (fileMtime < mtime) {
                        String fileMd5 = MD5Generator.getDigestString(file);
                        if (!fileMd5.equals(md5)) {
                            log.info("Found agent plugin in the DB that is newer than the one we have: " + name);
                            Plugin plugin = new Plugin(name, path, md5);
                            plugin.setMtime(mtime);
                            updatedPlugins.add(plugin);
                        } else {
                            file.setLastModified(mtime); // its up-to-date, set the file mtime so we don't check MD5 again
                        }
                    }
                } else {
                    log.info("Found agent plugin in the DB that we do not yet have: " + name);
                    Plugin plugin = new Plugin(name, path, md5);
                    plugin.setMtime(mtime);
                    updatedPlugins.add(plugin);
                }
            }
            JDBCUtil.safeClose(ps, rs);

            // write all our updated plugins to the file system
            ps = conn.prepareStatement("SELECT CONTENT FROM " + Plugin.TABLE_NAME + " WHERE NAME = ?");
            for (Plugin plugin : updatedPlugins) {
                File file = new File(this.pluginDirectory, plugin.getPath());

                ps.setString(1, plugin.getName());
                rs = ps.executeQuery();
                rs.next();
                InputStream content = rs.getBinaryStream(1);
                StreamUtil.copy(content, new FileOutputStream(file));
                rs.close();
                file.setLastModified(plugin.getMtime()); // so our file matches the database mtime
            }
        } finally {
            JDBCUtil.safeClose(conn, ps, rs);
        }

        return;
    }

    /**
     * This method will stream up plugin content if the server has a plugin file
     * but there is null content in the database (only occurs when upgrading an old server to the new
     * schema that supports database-storage for plugins).
     */
    private void fixMissingPluginContent() throws Exception {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        // This map contains the names/paths of plugins that are missing their content in the database.
        // This map will only have entries if this server was recently upgraded from an older version
        // that did not support database-stored plugin content.
        Map<String, String> pluginsMissingContentInDb = new HashMap<String, String>();

        try {
            DataSource ds = LookupUtil.getDataSource();
            conn = ds.getConnection();
            ps = conn.prepareStatement("SELECT NAME, PATH FROM " + Plugin.TABLE_NAME + " WHERE CONTENT IS NULL");
            rs = ps.executeQuery();
            while (rs.next()) {
                String name = rs.getString(1);
                String path = rs.getString(2);
                pluginsMissingContentInDb.put(name, path);
            }
        } finally {
            JDBCUtil.safeClose(conn, ps, rs);
        }

        for (Map.Entry<String, String> entry : pluginsMissingContentInDb.entrySet()) {
            String name = entry.getKey();
            String path = entry.getValue();
            File pluginFile = new File(this.pluginDirectory, path);
            if (pluginFile.exists()) {
                streamPluginFileContentToDatabase(name, pluginFile);
                log.info("Populating the missing content for plugin [" + name + "] file=" + pluginFile);
            } else {
                throw new Exception("The database knows of a plugin named [" + name + "] with path [" + path
                    + "] but the content is missing. This server does not have this plugin at [" + pluginFile
                    + "] so the database cannot be updated with the content.");
            }
        }

        return;
    }

    /**
     * This will write the contents of the given plugin file to the database.
     * This will store both the contents and the MD5 in an atomic transaction
     * so they remain insync.
     * 
     * @param name the name of the plugin whose content is being updated
     * @param file the plugin file whose content will be streamed to the database
     * 
     * @throws Exception
     */
    private void streamPluginFileContentToDatabase(String name, File file) throws Exception {
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        TransactionManager tm = null;

        String sql = "UPDATE " + Plugin.TABLE_NAME + " SET CONTENT = ?, MD5 = ?, MTIME = ? WHERE NAME = ?";

        String md5 = MD5Generator.getDigestString(file);
        long mtime = file.lastModified();
        FileInputStream fis = new FileInputStream(file);

        try {
            tm = LookupUtil.getTransactionManager();
            tm.begin();
            DataSource ds = LookupUtil.getDataSource();
            conn = ds.getConnection();
            ps = conn.prepareStatement(sql);
            ps.setBinaryStream(1, new BufferedInputStream(fis), (int) file.length());
            ps.setString(2, md5);
            ps.setLong(3, mtime);
            ps.setString(4, name);
            int updateResults = ps.executeUpdate();
            if (updateResults == 1) {
                log.debug("Stored content for plugin [" + name + "] in the db. file=" + file);
            } else {
                throw new Exception("Failed to update content for plugin [" + name + "] from [" + file + "]");
            }
        } catch (Exception e) {
            tm.rollback();
            tm = null;
            throw e;
        } finally {
            JDBCUtil.safeClose(conn, ps, rs);

            try {
                fis.close();
            } catch (Throwable t) {
            }

            if (tm != null) {
                tm.commit();
            }
        }
        return;
    }
}
