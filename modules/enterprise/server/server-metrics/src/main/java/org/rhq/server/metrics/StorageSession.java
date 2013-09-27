package org.rhq.server.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Host;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.Query;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.NoHostAvailableException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * @author John Sanda
 */
public class StorageSession implements Host.StateListener {

    private final Log log = LogFactory.getLog(StorageSession.class);

    private Session wrappedSession;

    private List<StorageStateListener> listeners = new ArrayList<StorageStateListener>();

    public StorageSession(Session wrappedSession) {
        this.wrappedSession = wrappedSession;
        this.wrappedSession.getCluster().register(this);
    }

    public void addStorageStateListener(StorageStateListener listener) {
        listeners.add(listener);
    }

    public ResultSet execute(String query) {
        try {
            return wrappedSession.execute(query);
        } catch (NoHostAvailableException e) {
            fireClusterDownEvent(e);
            throw e;
        }
    }

    public ResultSet execute(Query query) {
        try {
            return wrappedSession.execute(query);
        } catch (NoHostAvailableException e) {
            fireClusterDownEvent(e);
            throw e;
        }
    }

    public StorageResultSetFuture executeAsync(String query) {
        ResultSetFuture future = wrappedSession.executeAsync(query);
        return new StorageResultSetFuture(future, this);
    }

    public StorageResultSetFuture executeAsync(Query query) {
        ResultSetFuture future = wrappedSession.executeAsync(query);
        return new StorageResultSetFuture(future, this);
    }

    public PreparedStatement prepare(String query) {
        return wrappedSession.prepare(query);
    }

    public void shutdown() {
        wrappedSession.shutdown();
    }

    public boolean shutdown(long timeout, TimeUnit unit) {
        return wrappedSession.shutdown(timeout, unit);
    }

    public Cluster getCluster() {
        return wrappedSession.getCluster();
    }

    @Override
    public void onAdd(Host host) {
        log.info(host + " added");
        for (StorageStateListener listener : listeners) {
            listener.onStorageNodeUp(host.getAddress());
        }
    }

    @Override
    public void onUp(Host host) {
        log.info(host + " is up");
        for (StorageStateListener listener : listeners) {
            listener.onStorageNodeUp(host.getAddress());
        }
    }

    @Override
    public void onDown(Host host) {
        log.info(host + " is down");
        for (StorageStateListener listener : listeners) {
            listener.onStorageNodeDown(host.getAddress());
        }
    }

    @Override
    public void onRemove(Host host) {
        log.info(host + " has been removed");
        for (StorageStateListener listener : listeners) {
            listener.onStorageNodeRemoved(host.getAddress());
        }
    }

    void fireClusterDownEvent(NoHostAvailableException e) {
        for (StorageStateListener listener : listeners) {
            listener.onStorageClusterDown(e);
        }
    }
}