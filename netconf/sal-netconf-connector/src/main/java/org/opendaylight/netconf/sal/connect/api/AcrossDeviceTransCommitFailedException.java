package org.opendaylight.netconf.sal.connect.api;

import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.yangtools.yang.common.RpcError;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;
import org.opendaylight.yangtools.yang.common.RpcError.ErrorType;


public class AcrossDeviceTransCommitFailedException extends TransactionCommitFailedException{
    
    private static final long serialVersionUID = -1871066514796231632L;

    protected static RpcError[] toExceptionRpcErrors (String message) {
        return new RpcError[]{RpcResultBuilder.newError(ErrorType.TRANSPORT, "exception-caught", message)}; 
    } 
    
    public AcrossDeviceTransCommitFailedException(String message, RpcError[] errors) {
        super(message, null, errors); 
    }   
    
    public AcrossDeviceTransCommitFailedException(String message, Throwable cause, RpcError[] errors) { 
        super(message, cause, errors);
    }
    
    public AcrossDeviceTransCommitFailedException(String message, Throwable cause) { 
        super(message, cause, toExceptionRpcErrors(message));
    }   


}
