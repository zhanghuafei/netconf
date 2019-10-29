/*
 * Copyright (c) 2019 UTStarcom, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.netconf.sal.connect.netconf.sal;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import org.opendaylight.mdsal.binding.dom.codec.api.BindingNormalizedNodeSerializer;
import org.opendaylight.mdsal.dom.api.DOMMountPoint;
import org.opendaylight.mdsal.dom.api.DOMMountPointService;
import org.opendaylight.mdsal.dom.api.DOMRpcResult;
import org.opendaylight.mdsal.dom.api.DOMRpcService;
import org.opendaylight.netconf.api.xml.XmlUtil;
import org.opendaylight.netconf.sal.connect.netconf.util.NetconfBaseOps;
import org.opendaylight.netconf.sal.connect.netconf.util.NetconfMessageTransformUtil;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.NetworkTopology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.Topology;
import org.opendaylight.yang.gen.v1.urn.tbd.params.xml.ns.yang.network.topology.rev131021.network.topology.topology.Node;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.*;
import org.opendaylight.yangtools.yang.data.impl.schema.Builders;
import org.opendaylight.yangtools.yang.data.impl.schema.builder.api.NormalizedNodeAttrBuilder;
import org.opendaylight.yangtools.yang.model.api.SchemaPath;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.transform.dom.DOMSource;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

import static org.opendaylight.netconf.sal.connect.netconf.util.NetconfMessageTransformUtil.*;

/**
 * Netconf分页功能
 * <p>
 * Author: Huafei Zhang
 */
public class NetconfPagingServiceImpl implements NetconfPagingService {

    private DOMMountPointService domMountService;
    final private BindingNormalizedNodeSerializer codec;

    private static final String DEFAULT_TOPOLOGY_NAME = "topology-netconf";
    private static final YangInstanceIdentifier DEFAULT_TOPOLOGY_NODE =
            YangInstanceIdentifier.builder().node(NetworkTopology.QNAME).node(Topology.QNAME)
                    .nodeWithKey(Topology.QNAME, QName.create(Topology.QNAME, "topology-id"), DEFAULT_TOPOLOGY_NAME)
                    .node(Node.QNAME).build();

    private static final String XPATH = "xpath";
    private static final QName NETCONF_SELECT_QNAME = QName.create(NETCONF_QNAME, "select").intern();
    private static final String NAMESPACE_PREFIX = "t";

    public NetconfPagingServiceImpl(BindingNormalizedNodeSerializer codec, DOMMountPointService domMountService) {
        this.codec = codec;
        this.domMountService = domMountService;
    }

    private YangInstanceIdentifier toYangNodeII(String nodeId) {
        return YangInstanceIdentifier.builder(DEFAULT_TOPOLOGY_NODE)
                .nodeWithKey(Node.QNAME, QName.create(Node.QNAME, "node-id"), nodeId).build();
    }

    public <T extends DataObject> ListenableFuture<Optional<T>> find(String nodeId, final Class<T> topContainer,
                                                                     int start, int end) {
        try {
            Preconditions.checkArgument(start > 0 && end > 0 && start < end);
            YangInstanceIdentifier yangII = toTableYangII(topContainer);
            YangInstanceIdentifier nodeII = toYangNodeII(nodeId);
            Optional<DOMMountPoint> mountPointOpt = domMountService.getMountPoint(nodeII);
            if (!mountPointOpt.isPresent()) {
                SettableFuture<Optional<T>> future = SettableFuture.create();
                future.setException(new IllegalStateException("Specified mount point " + nodeId + " not exist"));
                return future;
            }

            DOMRpcService rpcService = mountPointOpt.get().getService(DOMRpcService.class).get();
            SchemaPath type = SchemaPath.create(true, NETCONF_GET_CONFIG_QNAME);
            final NormalizedNodeAttrBuilder<YangInstanceIdentifier.NodeIdentifier, DOMSource, AnyXmlNode> anyXmlBuilder =
                    toFitlerStructure(topContainer, start, end);
            DataContainerChild<?, ?> invokeInput = NetconfMessageTransformUtil.wrap(NETCONF_GET_CONFIG_QNAME,
                    NetconfBaseOps.getSourceNode(NETCONF_RUNNING_QNAME), anyXmlBuilder.build());

            FluentFuture<DOMRpcResult> resultFuture = rpcService.invokeRpc(type, invokeInput);
            return resultFuture.transform(domRpcResult -> {
                Preconditions.checkArgument(domRpcResult.getErrors().isEmpty(), "Unable to read data: %s, errors: %s",
                        topContainer.getSimpleName(), domRpcResult.getErrors());
                final DataContainerChild<? extends YangInstanceIdentifier.PathArgument, ?> dataNode =
                        ((ContainerNode) domRpcResult.getResult())
                                .getChild(NetconfMessageTransformUtil.toId(NETCONF_DATA_QNAME)).get();

                java.util.Optional<NormalizedNode<?, ?>> normalizedNodeOptional =
                        NormalizedNodes.findNode(dataNode, yangII.getPathArguments());
                if (!normalizedNodeOptional.isPresent()) {
                    return Optional.absent();
                }

                return Optional.of((T) codec.fromNormalizedNode(yangII, dataNode).getValue());
            }, MoreExecutors.directExecutor());
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            SettableFuture future = SettableFuture.create();
            future.setException(e);
            return future;
        }
    }

    private <T extends DataObject> YangInstanceIdentifier toTableYangII(Class<T> topContainer)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = topContainer.getField("QNAME");
        QName qname = (QName) field.get(null);
        return YangInstanceIdentifier.builder().node(qname).build();
    }

    private <T extends DataObject> NormalizedNodeAttrBuilder<YangInstanceIdentifier.NodeIdentifier, DOMSource, AnyXmlNode> toFitlerStructure(
            Class<T> topContainer, int start, int end) throws IllegalAccessException, NoSuchFieldException {
        final NormalizedNodeAttrBuilder<YangInstanceIdentifier.NodeIdentifier, DOMSource, AnyXmlNode> anyXmlBuilder =
                Builders.anyXmlBuilder().withNodeIdentifier(new YangInstanceIdentifier.NodeIdentifier(
                        QName.create(NETCONF_QNAME, NETCONF_FILTER_QNAME.getLocalName()).intern()));
        Map<QName, String> attributes = Maps.newHashMap();
        anyXmlBuilder.withAttributes(attributes);

        Document doc = XmlUtil.newDocument();
        final Element element =
                doc.createElementNS(NETCONF_FILTER_QNAME.getNamespace().toString(), NETCONF_FILTER_QNAME.getLocalName());
        element.setAttribute(NETCONF_TYPE_QNAME.getLocalName(), XPATH);
        element.setAttribute(NETCONF_SELECT_QNAME.getLocalName(), toXpathExp(topContainer.getSimpleName(), start, end));

        String namespace = extractNamespace(topContainer);
        element.setAttribute(XmlUtil.XMLNS_ATTRIBUTE_KEY + ":" + NAMESPACE_PREFIX, namespace);
        anyXmlBuilder.withValue(new DOMSource(element));
        return anyXmlBuilder;
    }

    private <T extends DataObject> String extractNamespace(Class<T> topContainer)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = topContainer.getField("QNAME");
        QName qName = (QName) field.get(null);
        return qName.getNamespace().toString();
    }

    /**
     * Example: L3VPNStaticIpv4RouteCfgs:/t:L3VPNStaticIpv4RouteCfg[/t:id >='0'][/t:id <='200']
     */
    private String toXpathExp(String topContainerName, int start, int end) {
        String prefixSlash = "/" + NAMESPACE_PREFIX + ":";
        String listName = topContainerName.substring(0, topContainerName.length() - 1);
        return prefixSlash + topContainerName + prefixSlash + listName + "[" + prefixSlash + "id >=" + "'" + start + "'"
                + "]" + "[" + prefixSlash + "id <=" + "'" + end + "'" + "]";
    }
}
