/*
 * Copyright (c) 2018 UTStarcom, Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.netconf.sal.connect.api;

/**
 * Session or connection abruptly breaking during tx may lead unhealthy transaction state.
 * 
 * @author Zhang Huafei
 *
 */
public class AcrossDeviceTransPartialUnheathyException extends AcrossDeviceTransCommitFailedException {

    private static final long serialVersionUID = 6403262059003146401L;

    public AcrossDeviceTransPartialUnheathyException(String message, Throwable cause) {
        super(message, cause, toExceptionRpcErrors(message));
    }

}
