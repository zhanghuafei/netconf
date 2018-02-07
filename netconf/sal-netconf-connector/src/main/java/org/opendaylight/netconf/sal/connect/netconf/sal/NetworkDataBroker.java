package org.opendaylight.netconf.sal.connect.netconf.sal;

import org.opendaylight.netconf.sal.connect.netconf.sal.tx.NetworkWriteTransaction;

public interface NetworkDataBroker {
    
    NetworkWriteTransaction newWriteOnlyTransaction();
}
