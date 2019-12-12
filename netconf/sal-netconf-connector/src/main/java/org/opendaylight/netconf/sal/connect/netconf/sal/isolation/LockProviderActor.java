package org.opendaylight.netconf.sal.connect.netconf.sal.isolation;

import java.util.concurrent.DelayQueue;

public class LockProviderActor {
    private DelayQueue queue = new DelayQueue();

    public boolean tryAcquire(String nodeId) {
        return true;
    }

    public void release() {

    }

}
