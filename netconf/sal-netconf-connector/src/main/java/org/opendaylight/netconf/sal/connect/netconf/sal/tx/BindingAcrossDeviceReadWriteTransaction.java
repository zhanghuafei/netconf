/*
 * Copyright (c) 2018 UTStarcom, Inc. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.netconf.sal.connect.netconf.sal.tx;

import org.opendaylight.controller.md.sal.common.api.MappingCheckedFuture;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.ReadFailedException;
import org.opendaylight.controller.md.sal.dom.api.DOMDataBroker;
import org.opendaylight.controller.md.sal.dom.api.DOMDataReadOnlyTransaction;
import org.opendaylight.controller.md.sal.dom.api.DOMMountPoint;
import org.opendaylight.controller.md.sal.dom.api.DOMMountPointService;
import org.opendaylight.mdsal.binding.dom.codec.api.BindingNormalizedNodeSerializer;
import org.opendaylight.netconf.sal.connect.netconf.sal.isolation.TransactionScheduler;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yangtools.util.concurrent.ExceptionMapper;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.CheckedFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;

@SuppressWarnings("deprecation")
public class BindingAcrossDeviceReadWriteTransaction extends BindingAcrossDeviceWriteTransaction implements
        AcrossDeviceReadWriteTransaction {
    private static final Logger LOG = LoggerFactory.getLogger(BindingAcrossDeviceReadWriteTransaction.class);
    private BindingNormalizedNodeSerializer codec;
    private DOMMountPointService mountService;

    public BindingAcrossDeviceReadWriteTransaction(BindingNormalizedNodeSerializer codec,
                                                   DOMMountPointService mountService, TransactionScheduler transScheduler) {
        super(codec, mountService, transScheduler);
        this.codec = codec;
        this.mountService = mountService;
    }

    @Override
    public <T extends DataObject> CheckedFuture<Optional<T>, ReadFailedException> read(
            InstanceIdentifier<?> mountPointPath, LogicalDatastoreType store, InstanceIdentifier<T> path) {
        String nodeId = toNodeId(mountPointPath);
        YangInstanceIdentifier dataPath = codec.toYangInstanceIdentifier(path);
        ExceptionMapper mapper = new ExceptionMapper<ReadFailedException>("read", ReadFailedException.class) {
            @Override
            protected ReadFailedException newWithCause(String message, Throwable cause) {
                return new ReadFailedException("ne-id=" + nodeId + ": " + message, cause);
            }
        };

        CheckedFuture<Optional<NormalizedNode<?,?>>, ReadFailedException> readfuture = read(mountPointPath, store, dataPath);

        return MappingCheckedFuture.create(
                Futures.transform(readfuture, new DeserializeFunction<T>(dataPath)), mapper);
    }

    @Override
    public CheckedFuture<Optional<NormalizedNode<?,?>>, ReadFailedException> read(
            InstanceIdentifier<?> mountPointPath, LogicalDatastoreType store, YangInstanceIdentifier dataPath) {
        YangInstanceIdentifier yangMountPointPath = codec.toYangInstanceIdentifier(mountPointPath);
        String nodeId = toNodeId(mountPointPath);
        ExceptionMapper mapper = new ExceptionMapper<ReadFailedException>("read", ReadFailedException.class) {
            @Override
            protected ReadFailedException newWithCause(String message, Throwable cause) {
                return new ReadFailedException("ne-id=" + nodeId + ": " + message, cause);
            }
        };
        Optional<DOMMountPoint> optionalMountPoint = mountService.getMountPoint(yangMountPointPath);
        if (!optionalMountPoint.isPresent()) {
            SettableFuture<Optional<NormalizedNode<?,?>>> future = SettableFuture.create();
            String message = "Mount point not exist: " + nodeId;
            LOG.error(message);
            future.setException(new IllegalStateException(message));
            return MappingCheckedFuture.create(future, mapper);
        }
        DOMMountPoint mountPoint = optionalMountPoint.get();
        // I think omitting optional check is ok.
        DOMDataBroker db = mountPoint.getService(DOMDataBroker.class).get();
        DOMDataReadOnlyTransaction tx = db.newReadOnlyTransaction();
        return tx.read(store, dataPath);
    }

    /**
     * Convert normalize node to binding data object.
     */
    private final class DeserializeFunction<S> implements Function<Optional<NormalizedNode<?, ?>>, Optional<S>> {
        private YangInstanceIdentifier dataPath;

        public DeserializeFunction(YangInstanceIdentifier dataPath) {
            this.dataPath = dataPath;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Optional<S> apply(final Optional<NormalizedNode<?, ?>> input) {
            if (input.isPresent()) {
                return Optional.of((S) codec.fromNormalizedNode(dataPath, input.get()).getValue());
            }
            return Optional.absent();
        }
    }

}
