package org.opendaylight.netconf.sal.connect.netconf.sal;

import org.opendaylight.controller.md.sal.dom.api.DOMMountPointService;
import org.opendaylight.mdsal.binding.dom.codec.api.BindingNormalizedNodeSerializer;
import org.opendaylight.netconf.sal.connect.netconf.sal.tx.AcrossDeviceWriteTransaction;
import org.opendaylight.netconf.sal.connect.netconf.sal.tx.BindingAcrossDeviceWriteTransaction;

public class AcrossDeviceDataBrokerImpl implements AcrossDeviceDataBroker {

    private BindingNormalizedNodeSerializer codec;
    private DOMMountPointService mountService;

    public AcrossDeviceDataBrokerImpl(BindingNormalizedNodeSerializer codec, DOMMountPointService mountService) {
        this.codec = codec;
        this.mountService = mountService;
    }

    @Override
    public AcrossDeviceWriteTransaction newWriteOnlyTransaction() { 
        return new BindingAcrossDeviceWriteTransaction(codec, mountService);
    }
}
