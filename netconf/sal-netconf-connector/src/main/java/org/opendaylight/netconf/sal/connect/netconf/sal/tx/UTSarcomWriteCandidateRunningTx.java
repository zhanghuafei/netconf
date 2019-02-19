/*
 * Copyright (c) 2019 UTStarcom, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */


package org.opendaylight.netconf.sal.connect.netconf.sal.tx;

import org.opendaylight.netconf.sal.connect.netconf.util.NetconfBaseOps;
import org.opendaylight.netconf.sal.connect.netconf.util.NetconfRpcFutureCallback;
import org.opendaylight.netconf.sal.connect.util.RemoteDeviceId;

/**
 * to reuse UTStarcomWriteCandidateTx
 * 
 * @author Zhang Huafei
 */
public class UTSarcomWriteCandidateRunningTx extends UTStarcomWriteCandidateTx {

    public UTSarcomWriteCandidateRunningTx(final RemoteDeviceId id, final NetconfBaseOps netOps,
                                   final boolean rollbackSupport) {
        super(id, netOps, rollbackSupport);
    }

    @Override
    protected synchronized void init() {
        lockRunning();
        super.init();
    }

    @Override
    protected void cleanupOnSuccess() {
        super.cleanupOnSuccess();
        unlockRunning();
    }

    private void lockRunning() {
        resultsFutures.add(netOps.lockRunning(new NetconfRpcFutureCallback("Lock running", id)));
    }

    /**
     * This has to be non blocking since it is called from a callback on commit
     * and its netty threadpool that is really sensitive to blocking calls.
     */
    private void unlockRunning() {
        netOps.unlockRunning(new NetconfRpcFutureCallback("Unlock running", id));
    }
}
