package org.opendaylight.netconf.sal.connect.netconf.sal;

import org.opendaylight.netconf.sal.connect.netconf.sal.tx.AcrossDeviceWriteTransaction;

public interface AcrossDeviceDataBroker {
    
    AcrossDeviceWriteTransaction newWriteOnlyTransaction();
}
