/*
 * Copyright (c) 2016 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.netconf.sal.connect.netconf.util;

import com.google.common.base.Optional;

import org.opendaylight.yangtools.yang.data.api.ModifyAction;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.AnyXmlNode;
import org.opendaylight.yangtools.yang.data.api.schema.DataContainerChild;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNodes;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;

import java.util.Arrays;

/**
 * Transforms rpc structures to normalized nodes and vice versa.
 */
class NetconfRpcStructureTransformer implements RpcStructureTransformer {

    private final SchemaContext schemaContext;
    private final SchemaContext gctx;

    NetconfRpcStructureTransformer(final SchemaContext schemaContext, final SchemaContext gctx) {
        this.schemaContext = schemaContext;
        this.gctx = gctx;
    }

    @Override
    public Optional<NormalizedNode<?, ?>> selectFromDataStructure(
            final DataContainerChild<? extends YangInstanceIdentifier.PathArgument, ?> data,
            final YangInstanceIdentifier path) {
        return Optional.fromJavaUtil(NormalizedNodes.findNode(data, path.getPathArguments()));
    }

    @Override
    public AnyXmlNode createEditConfigStructure(final Optional<NormalizedNode<?, ?>> data,
                                                final YangInstanceIdentifier dataPath,
                                                final Optional<ModifyAction> operation) {
    	// 如果是UT的数据，使用global SchemaContext
    	if(dataPath.toString().contains("utstar") && isFromBinding()) {
    	    return NetconfMessageTransformUtil.createEditConfigAnyxml(gctx, dataPath, operation, data);
    	}
        return NetconfMessageTransformUtil.createEditConfigAnyxml(schemaContext, dataPath, operation, data);
    }

    private boolean isFromBinding() {
        StackTraceElement[] stackElements = new Throwable().getStackTrace();
        return Arrays.stream(stackElements).anyMatch(e -> e.getClassName().contains("BindingDOM"));
    }

    @Override
    public DataContainerChild<?, ?> toFilterStructure(final YangInstanceIdentifier path) {
        // 如果是UT的数据，使用global SchemaContext
        if(path.toString().contains("utstar") && isFromBinding()) {
            return NetconfMessageTransformUtil.toFilterStructure(path, gctx);
        }
        return NetconfMessageTransformUtil.toFilterStructure(path, schemaContext);
    }
}
