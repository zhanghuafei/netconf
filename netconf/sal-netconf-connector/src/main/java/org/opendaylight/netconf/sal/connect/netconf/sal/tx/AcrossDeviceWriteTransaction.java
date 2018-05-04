package org.opendaylight.netconf.sal.connect.netconf.sal.tx;

import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;

import com.google.common.util.concurrent.CheckedFuture;

public interface AcrossDeviceWriteTransaction {
  public <T extends DataObject> void put(InstanceIdentifier<?> mountPointPath, LogicalDatastoreType store,
      InstanceIdentifier<T> path, T data);
  
  public <T extends DataObject> void delete (InstanceIdentifier<?> mountPointPath, LogicalDatastoreType store,
          InstanceIdentifier<T> path);
  
  public <T extends DataObject> void merge(InstanceIdentifier<?> mountPointPath, LogicalDatastoreType store,
          InstanceIdentifier<T> path, T data);
  
  public CheckedFuture<Void, TransactionCommitFailedException> submit();

}
