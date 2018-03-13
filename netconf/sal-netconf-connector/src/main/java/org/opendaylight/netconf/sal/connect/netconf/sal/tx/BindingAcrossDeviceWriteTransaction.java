package org.opendaylight.netconf.sal.connect.netconf.sal.tx;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.opendaylight.controller.config.util.xml.DocumentedException;
import org.opendaylight.controller.md.sal.common.api.TransactionStatus;
import org.opendaylight.controller.md.sal.common.api.data.LogicalDatastoreType;
import org.opendaylight.controller.md.sal.common.api.data.TransactionCommitFailedException;
import org.opendaylight.controller.md.sal.dom.api.DOMDataBroker;
import org.opendaylight.controller.md.sal.dom.api.DOMDataWriteTransaction;
import org.opendaylight.controller.md.sal.dom.api.DOMMountPoint;
import org.opendaylight.controller.md.sal.dom.api.DOMMountPointService;
import org.opendaylight.mdsal.binding.dom.codec.api.BindingNormalizedNodeSerializer;
import org.opendaylight.netconf.api.NetconfDocumentedException;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.CheckedFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
@SuppressWarnings("deprecation")  
public class BindingAcrossDeviceWriteTransaction implements AcrossDeviceWriteTransaction {

    private BindingNormalizedNodeSerializer codec;
    private DOMMountPointService mountService;


    private Map<YangInstanceIdentifier, DOMDataWriteTransaction> mountPointPathToTx = Maps.newHashMap(); // cohorts

    public BindingAcrossDeviceWriteTransaction(BindingNormalizedNodeSerializer codec, DOMMountPointService mountService) {
        this.codec = codec;
        this.mountService = mountService;
    }

    private DOMDataWriteTransaction getOrCreate(InstanceIdentifier<?> mountPointPath,
            YangInstanceIdentifier yangMountPointPath) {
        DOMDataWriteTransaction tx = mountPointPathToTx.get(mountPointPath);
        if (tx == null) {
            Optional<DOMMountPoint> optionalMountPoint = mountService.getMountPoint(yangMountPointPath);
            if (!optionalMountPoint.isPresent()) {
                final SettableFuture<RpcResult<TransactionStatus>> txResult = SettableFuture.create();
                txResult.setException(new IllegalStateException("Mount point " + mountPointPath + " not exist."));
            }
            DOMMountPoint mountPoint = optionalMountPoint.get();
            DOMDataBroker db = mountPoint.getService(DOMDataBroker.class).get(); // I think not check optional should be ok.
            tx = db.newWriteOnlyTransaction();
            mountPointPathToTx.put(mountPoint.getIdentifier(), tx);
        }
        return tx;
    }

    private ListenableFuture<RpcResult<TransactionStatus>> performCommit() {
        ListenableFuture<RpcResult<TransactionStatus>> voteResult = toVoteResult();

        List<ListenableFuture<RpcResult<TransactionStatus>>> txResults = Lists.newArrayList();
        mountPointPathToTx.entrySet().stream()
                .forEach(entry -> txResults.add(((AbstractWriteTx) entry.getValue()).performCommit(voteResult)));
        final SettableFuture<RpcResult<TransactionStatus>> transformed = SettableFuture.create();

        Futures.addCallback(Futures.allAsList(txResults), new FutureCallback<List<RpcResult<TransactionStatus>>>() {
            @Override
            public void onSuccess(final List<RpcResult<TransactionStatus>> txResults) {
                txResults.forEach(txResult -> {
                    if (!txResult.isSuccessful() && !transformed.isDone()) {
                        RpcResult<TransactionStatus> result =
                                RpcResultBuilder.<TransactionStatus>failed().withResult(TransactionStatus.FAILED)
                                        .withRpcErrors(txResult.getErrors()).build();
                        transformed.set(result);
                    }
                });

                if (!transformed.isDone()) {
                    transformed.set(RpcResultBuilder.success(TransactionStatus.COMMITED).build());
                }
            }

            @Override
            public void onFailure(final Throwable throwable) {
                final NetconfDocumentedException exception =
                        new NetconfDocumentedException(":RPC during tx returned an exception",
                                new Exception(throwable), DocumentedException.ErrorType.APPLICATION,
                                DocumentedException.ErrorTag.OPERATION_FAILED, DocumentedException.ErrorSeverity.ERROR);
                transformed.setException(exception);
            }
        });
        return transformed;

    }

    private ListenableFuture<RpcResult<TransactionStatus>> toVoteResult() {
        List<ListenableFuture<RpcResult<Void>>> txResults = Lists.newArrayList();
        mountPointPathToTx.entrySet().stream()
                .forEach(entry -> txResults.add(((AbstractWriteTx) entry.getValue()).resultsToTxStatus()));
        final SettableFuture<RpcResult<TransactionStatus>> transformed = SettableFuture.create();

        Futures.addCallback(Futures.allAsList(txResults), new FutureCallback<List<RpcResult<Void>>>() {
            @Override
            public void onSuccess(final List<RpcResult<Void>> txResults) {
                txResults.forEach(txResult -> {
                    if (!txResult.isSuccessful() && !transformed.isDone()) {
                        RpcResult<TransactionStatus> result =
                                RpcResultBuilder.<TransactionStatus>failed().withResult(TransactionStatus.FAILED)
                                        .withRpcErrors(txResult.getErrors()).build();
                        transformed.set(result);
                    }
                });

                if (!transformed.isDone()) {
                    transformed.set(RpcResultBuilder.success(TransactionStatus.COMMITED).build());
                }
            }

            @Override
            public void onFailure(final Throwable throwable) {
                final NetconfDocumentedException exception =
                        new NetconfDocumentedException(":RPC during tx returned an exception",
                                new Exception(throwable), DocumentedException.ErrorType.APPLICATION,
                                DocumentedException.ErrorTag.OPERATION_FAILED, DocumentedException.ErrorSeverity.ERROR);
                transformed.setException(exception);
            }
        });
        return transformed;

    }

    @Override
    public CheckedFuture<Void, TransactionCommitFailedException> submit() {
        ListenableFuture<RpcResult<TransactionStatus>> netTxStatus = performCommit();
        Futures.addCallback(netTxStatus, new FutureCallback<RpcResult<TransactionStatus>>() {

            @Override
            public void onSuccess(RpcResult<TransactionStatus> result) {
                if (result.isSuccessful()) {
                    cleanupOnSuccess();
                } else {
                    cleanup();
                }
            }

            @Override
            public void onFailure(Throwable t) {
                cleanup();
            }

            private void cleanupOnSuccess() {
                mountPointPathToTx.values().stream().forEach(tx -> ((WriteCandidateTx) tx).cleanupOnSuccess());
            }

            private void cleanup() {
                mountPointPathToTx.values().stream().forEach(tx -> ((WriteCandidateTx) tx).cleanup());
            }

        });

        final ListenableFuture<Void> commitFutureAsVoid =
                Futures.transform(netTxStatus, new Function<RpcResult<TransactionStatus>, Void>() {
                    @Override
                    public Void apply(final RpcResult<TransactionStatus> input) {
                        Preconditions.checkArgument(input.isSuccessful() && input.getErrors().isEmpty(),
                                "Submit of net transaction failed with error: %s", input.getErrors());
                        return null;
                    }
                });

        return Futures.makeChecked(commitFutureAsVoid, new Function<Exception, TransactionCommitFailedException>() {
            @Override
            public TransactionCommitFailedException apply(final Exception input) {
                if (input.getCause() instanceof IllegalArgumentException) {
                    IllegalArgumentException exception = (IllegalArgumentException) input.getCause();
                    return new TransactionCommitFailedException(exception.getMessage(), input);
                }
                return new TransactionCommitFailedException("Submit of transaction failed", input);
            }
        });
    }

    @Override
    public <T extends DataObject> void put(InstanceIdentifier<?> mountPointPath, LogicalDatastoreType store, InstanceIdentifier<T> path, T data) {
        YangInstanceIdentifier yangMountPointPath = codec.toYangInstanceIdentifier(mountPointPath);
        DOMDataWriteTransaction tx = getOrCreate(mountPointPath, yangMountPointPath);
        final Entry<YangInstanceIdentifier, NormalizedNode<?, ?>> normalized = codec.toNormalizedNode(path, data);
        tx.put(store, normalized.getKey(), normalized.getValue()); // may fail ?
    }
    
    @Override
    public <T extends DataObject> void delete(InstanceIdentifier<?> mountPointPath, LogicalDatastoreType store, InstanceIdentifier<T> path) {
        YangInstanceIdentifier yangMountPointPath = codec.toYangInstanceIdentifier(mountPointPath);
        YangInstanceIdentifier dataPath = codec.toYangInstanceIdentifier(path);
        DOMDataWriteTransaction tx = getOrCreate(mountPointPath, yangMountPointPath);
        tx.delete(store, dataPath); 
    }


    @Override
    public <T extends DataObject> void merge(InstanceIdentifier<?> mountPointPath, LogicalDatastoreType store, InstanceIdentifier<T> path,
            T data) {
        YangInstanceIdentifier yangMountPointPath = codec.toYangInstanceIdentifier(mountPointPath);
        DOMDataWriteTransaction tx = getOrCreate(mountPointPath, yangMountPointPath);
        final Entry<YangInstanceIdentifier, NormalizedNode<?, ?>> normalized = codec.toNormalizedNode(path, data);
        tx.merge(store, normalized.getKey(), normalized.getValue()); 
    }
}
