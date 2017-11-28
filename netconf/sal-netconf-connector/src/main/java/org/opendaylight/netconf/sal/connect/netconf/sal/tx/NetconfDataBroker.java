package org.opendaylight.netconf.sal.connect.netconf.sal.tx;

import org.opendaylight.controller.md.sal.binding.impl.BindingToNormalizedNodeCodec;
import org.opendaylight.controller.md.sal.dom.api.DOMMountPointService;

public class NetconfDataBroker {

    private BindingToNormalizedNodeCodec codec;
    private DOMMountPointService mountService;

    public NetconfDataBroker(BindingToNormalizedNodeCodec codec, DOMMountPointService mountService) {
        this.codec = codec;
        this.mountService = mountService;
    }

    public NetWriteTransaction newWriteOnlyTransaction() {
        return new NetBindingDomWriteCandidateTx(codec, mountService);
    }
}
