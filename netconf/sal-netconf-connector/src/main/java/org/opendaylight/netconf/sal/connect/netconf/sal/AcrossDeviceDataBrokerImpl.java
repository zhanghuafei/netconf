/*
 * Copyright (c) 2018 UTStarcom, Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.netconf.sal.connect.netconf.sal;

import org.opendaylight.controller.md.sal.dom.api.DOMMountPointService;
import org.opendaylight.mdsal.binding.dom.codec.api.BindingNormalizedNodeSerializer;
import org.opendaylight.netconf.sal.connect.netconf.sal.tx.AcrossDeviceReadWriteTransaction;
import org.opendaylight.netconf.sal.connect.netconf.sal.tx.AcrossDeviceWriteTransaction;
import org.opendaylight.netconf.sal.connect.netconf.sal.tx.BindingAcrossDeviceReadWriteTransaction;
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

    @Override
    public AcrossDeviceReadWriteTransaction newReadWriteTransaction() {
        return new BindingAcrossDeviceReadWriteTransaction(codec, mountService);
    }
}
