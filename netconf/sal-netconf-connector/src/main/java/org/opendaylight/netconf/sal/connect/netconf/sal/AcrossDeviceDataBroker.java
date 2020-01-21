/*
 * Copyright (c) 2018 UTStarcom, Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.netconf.sal.connect.netconf.sal;

import org.opendaylight.netconf.sal.connect.netconf.sal.tx.AcrossDeviceReadWriteTransaction;
import org.opendaylight.netconf.sal.connect.netconf.sal.tx.AcrossDeviceWriteTransaction;

/**
 * Across device dataBroker interface
 *
 * @author Zhang Huafei
 */
public interface AcrossDeviceDataBroker {

    AcrossDeviceWriteTransaction newWriteOnlyTransaction();

    AcrossDeviceReadWriteTransaction newReadWriteTransaction();

}
