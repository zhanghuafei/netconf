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
import org.opendaylight.netconf.sal.connect.netconf.sal.isolation.TransactionScheduler;
import org.opendaylight.netconf.sal.connect.netconf.sal.tx.AcrossDeviceReadWriteTransaction;
import org.opendaylight.netconf.sal.connect.netconf.sal.tx.AcrossDeviceWriteTransaction;
import org.opendaylight.netconf.sal.connect.netconf.sal.tx.BindingAcrossDeviceReadWriteTransaction;
import org.opendaylight.netconf.sal.connect.netconf.sal.tx.BindingAcrossDeviceWriteTransaction;

/**
 * AcrossDeviceDataBroker implementation
 *
 * @author Zhang Huafei
 */
public class AcrossDeviceDataBrokerImpl implements AcrossDeviceDataBroker, AutoCloseable {

    private BindingNormalizedNodeSerializer codec;
    private DOMMountPointService mountService;
    private TransactionScheduler transScheduler;

    public AcrossDeviceDataBrokerImpl(BindingNormalizedNodeSerializer codec, DOMMountPointService mountService, int waitTimeout, long transactionCreationInitialRateLimit, long taskCongestionWatermark, int rateLimitStepSize, int taskPostponeTimeMin, int taskPostponeTimeMax) {
        this.codec = codec;
        this.mountService = mountService;
        this.transScheduler = new TransactionScheduler(waitTimeout, transactionCreationInitialRateLimit, taskCongestionWatermark, rateLimitStepSize, taskPostponeTimeMin, taskPostponeTimeMax);
        transScheduler.start();
    }

    @Override
    public AcrossDeviceWriteTransaction newWriteOnlyTransaction() {
        return new BindingAcrossDeviceWriteTransaction(codec, mountService, transScheduler);
    }

    @Override
    public AcrossDeviceReadWriteTransaction newReadWriteTransaction() {
        return new BindingAcrossDeviceReadWriteTransaction(codec, mountService, transScheduler);
    }

    @Override
    public void close() throws Exception {
        transScheduler.close();
    }
}
