package org.opendaylight.netconf.sal.connect.api;

import org.opendaylight.yangtools.yang.common.RpcError;

/**
 * Session or connection abruptly breaking during tx may lead unhealthy transaction state.
 * 
 * @author HZ08849
 *
 */
public class AcrossDeviceTransPartialUnheathyException extends AcrossDeviceTransCommitFailedException {

    private static final long serialVersionUID = 6403262059003146401L;

    public AcrossDeviceTransPartialUnheathyException(String message, RpcError[] errors) {
        super(message, errors);  
    } 
    
    public AcrossDeviceTransPartialUnheathyException(String message, Throwable cause) {
        super(message, cause, toExceptionRpcErrors(message));  
    } 

}
