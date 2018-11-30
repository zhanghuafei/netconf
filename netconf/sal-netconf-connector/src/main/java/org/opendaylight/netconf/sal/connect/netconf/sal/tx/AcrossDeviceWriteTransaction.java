/*
 * Copyright (c) 2018 UTStarcom, Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.netconf.sal.connect.netconf.sal.tx;

import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;

import com.google.common.util.concurrent.CheckedFuture;

public interface AcrossDeviceWriteTransaction {
    public <T extends DataObject> void put(InstanceIdentifier<?> mountPointPath, LogicalDatastoreType store,
        InstanceIdentifier<T> path, T data);

    public <T extends DataObject> void delete(InstanceIdentifier<?> mountPointPath, LogicalDatastoreType store,
        InstanceIdentifier<T> path);

    public <T extends DataObject> void merge(InstanceIdentifier<?> mountPointPath, LogicalDatastoreType store,
        InstanceIdentifier<T> path, T data);

    public CheckedFuture<Void, TransactionCommitFailedException> submit();


    /**
     * Attempts to cancel execution of this transaction. This attempt will fail if the transaction has already completed
     * or has already been cancelled.
     * 
     * @return {@code false} if the task could not be cancelled, typically because it has already completed normally;
     *         {@code true} otherwise
     */
    public boolean cancel();

}
