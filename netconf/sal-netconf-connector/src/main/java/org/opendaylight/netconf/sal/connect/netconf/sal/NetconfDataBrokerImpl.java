package org.opendaylight.netconf.sal.connect.netconf.sal;

import org.opendaylight.controller.md.sal.dom.api.DOMMountPointService;
import org.opendaylight.mdsal.binding.dom.codec.api.BindingNormalizedNodeSerializer;
import org.opendaylight.netconf.sal.connect.netconf.sal.NetworkDataBroker;
import org.opendaylight.netconf.sal.connect.netconf.sal.tx.NetworkBindingDomWriteCandidateTx;
import org.opendaylight.netconf.sal.connect.netconf.sal.tx.NetworkWriteTransaction;

public class NetconfDataBrokerImpl implements NetworkDataBroker {

    private BindingNormalizedNodeSerializer codec;
    private DOMMountPointService mountService;

    public NetconfDataBrokerImpl(BindingNormalizedNodeSerializer codec, DOMMountPointService mountService) {
        this.codec = codec;
        this.mountService = mountService;
    }

    @Override
    public NetworkWriteTransaction newWriteOnlyTransaction() {
        return new NetworkBindingDomWriteCandidateTx(codec, mountService);
    }
}
