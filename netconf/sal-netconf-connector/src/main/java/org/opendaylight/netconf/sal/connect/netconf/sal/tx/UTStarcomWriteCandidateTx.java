/*
 * Copyright (c) 2019 UTStarcom, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.netconf.sal.connect.netconf.sal.tx;

import java.util.List;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;

import javax.annotation.Nullable;

import org.opendaylight.controller.md.sal.dom.api.DOMRpcResult;
import org.opendaylight.netconf.api.DocumentedException;
import org.opendaylight.netconf.api.NetconfDocumentedException;
import org.opendaylight.netconf.sal.connect.netconf.util.NetconfBaseOps;
import org.opendaylight.netconf.sal.connect.netconf.util.NetconfRpcFutureCallback;
import org.opendaylight.netconf.sal.connect.util.RemoteDeviceId;
import org.opendaylight.yangtools.yang.common.RpcError;
import org.opendaylight.yangtools.yang.common.RpcResult;
import org.opendaylight.yangtools.yang.common.RpcResultBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * add validate before commit.
 * 
 * @author ZhangHuafei
 *
 */
public class UTStarcomWriteCandidateTx extends WriteCandidateTx {

	private static final Logger LOG = LoggerFactory
			.getLogger(UTStarcomWriteCandidateTx.class);

	public UTStarcomWriteCandidateTx(final RemoteDeviceId id,
			final NetconfBaseOps rpc, final boolean rollbackSupport) {
		super(id, rpc, rollbackSupport);
	}

	private ListenableFuture<DOMRpcResult> validateCandidate() {
		return netOps.validateCandidate(new NetconfRpcFutureCallback(
				"Validate candidate", id));
	}

    public RemoteDeviceId remoteDeviceId() {
        return id;
    }
	
	public synchronized ListenableFuture<RpcResult<Void>> prepare() { 
		resultsFutures.add(validateCandidate());
		return resultsToTxStatus();
	}

	@Override
	public synchronized ListenableFuture<RpcResult<Void>> performCommit() {
		// phase 1: edit and validate
		ListenableFuture<RpcResult<Void>> editConfigResults = prepare();
		// phase 2: commit
		return doCommit(editConfigResults);
	}
	
	public ListenableFuture<RpcResult<Void>> doCommit(
			ListenableFuture<RpcResult<Void>> editConfigResults) {
		SettableFuture<RpcResult<Void>> txResult = SettableFuture.create();
		Futures.addCallback(editConfigResults,
				new FutureCallback<RpcResult<Void>>() {

					@Override
					public void onSuccess(RpcResult<Void> editResult) {
						Futures.addCallback(netOps
								.commit(new NetconfRpcFutureCallback("Commit",
										id)),
								new FutureCallback<DOMRpcResult>() {

									@Override
									public void onSuccess(
											DOMRpcResult commitResult) {
										if (!txResult.isDone()) {
											extractResult(
													Lists.newArrayList(commitResult),
													txResult);
										}
									}

									@Override
									public void onFailure(Throwable throwable) {
										final NetconfDocumentedException exception = new NetconfDocumentedException(
												extractErrorMessage(id, throwable),
												new Exception(throwable),
												DocumentedException.ErrorType.APPLICATION,
												DocumentedException.ErrorTag.OPERATION_FAILED,
												DocumentedException.ErrorSeverity.ERROR);
										txResult.setException(exception);
									}
								}, MoreExecutors.directExecutor());
					}

					@Override
					public void onFailure(Throwable throwable) {
						final NetconfDocumentedException exception = new NetconfDocumentedException(
								extractErrorMessage(id, throwable),
								new Exception(throwable),
								DocumentedException.ErrorType.APPLICATION,
								DocumentedException.ErrorTag.OPERATION_FAILED,
								DocumentedException.ErrorSeverity.ERROR);
						txResult.setException(exception);
					}

				}, MoreExecutors.directExecutor());

		Futures.addCallback(txResult, new FutureCallback<RpcResult<Void>>() {
			@Override
			public void onSuccess(@Nullable final RpcResult<Void> result) {
				cleanupOnSuccess();
			}

			@Override
			public void onFailure(final Throwable throwable) {
				// TODO If lock is cause of this failure cleanup will
				// issue warning log
				// cleanup is trying to do unlock, but this will fail
				cleanup();
			}
		}, MoreExecutors.directExecutor());

		return txResult;
	}
	
	/**
	 * extract and normalize error message
	 * 
	 * TODO: based test result, details may required some adjustment
	 */
	private String extractErrorMessage(RemoteDeviceId id, Throwable throwable) {
		if(throwable.getMessage() == null) {
			return String.format("%s:RPC during tx commit returned an exception", id); 
		}
		return throwable.getMessage();
	}

	/**
	 * Method access control lead to this duplicated code snippet.
	 */
	private void extractResult(final List<DOMRpcResult> domRpcResults,
			final SettableFuture<RpcResult<Void>> transformed) {
		for (final DOMRpcResult domRpcResult : domRpcResults) {
			if (!domRpcResult.getErrors().isEmpty()) {
				final RpcError error = domRpcResult.getErrors().iterator()
						.next();
				final RpcError.ErrorType errorType = error.getErrorType();
				final DocumentedException.ErrorType eType;
				switch (errorType) {
				case RPC:
					eType = DocumentedException.ErrorType.RPC;
					break;
				case PROTOCOL:
					eType = DocumentedException.ErrorType.PROTOCOL;
					break;
				case TRANSPORT:
					eType = DocumentedException.ErrorType.TRANSPORT;
					break;
				case APPLICATION:
					eType = DocumentedException.ErrorType.APPLICATION;
					break;
				default:
					eType = DocumentedException.ErrorType.APPLICATION;
					break;
				}
				final RpcError.ErrorSeverity severity = error.getSeverity();
				final DocumentedException.ErrorSeverity eSeverity;
				switch (severity) {
				case ERROR:
					eSeverity = DocumentedException.ErrorSeverity.ERROR;
					break;
				case WARNING:
					eSeverity = DocumentedException.ErrorSeverity.WARNING;
					break;
				default:
					eSeverity = DocumentedException.ErrorSeverity.ERROR;
					break;
				}
				final String message;
				if (error.getMessage() == null || error.getMessage().isEmpty()) {
					message = "RPC during tx failed";
				} else {
					message = id + ": " + error.getMessage();
				}
				final NetconfDocumentedException exception = new NetconfDocumentedException(
						message, eType, DocumentedException.ErrorTag.from(error
								.getTag()), eSeverity);
				transformed.setException(exception);
				return;
			}
		}

		transformed.set(RpcResultBuilder.<Void> success().build());
	}
}
