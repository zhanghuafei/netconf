/*
 * Copyright (c) 2018 UTStarcom, Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.netconf.sal.connect.netconf.sal.tx;

import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.ReadFailedException;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.CheckedFuture;

/**
 * Across device read write transaction interface
 *
 * @author Zhang Huafei
 */
public interface AcrossDeviceReadWriteTransaction extends AcrossDeviceWriteTransaction {
    @SuppressWarnings("deprecation")
    public <T extends DataObject> CheckedFuture<Optional<T>, ReadFailedException> read(
            InstanceIdentifier<?> mountPointPath, LogicalDatastoreType store, InstanceIdentifier<T> path);

}
