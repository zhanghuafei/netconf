/*
 * Copyright (c) 2019 UTStarcom, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.netconf.sal.connect.netconf.util;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import org.opendaylight.netconf.api.xml.XmlUtil;
import org.opendaylight.netconf.util.NetconfUtil;
import org.opendaylight.yangtools.yang.data.util.ParserStreamUtils;
import org.opendaylight.yangtools.yang.model.api.ContainerSchemaNode;
import org.opendaylight.yangtools.yang.model.api.DataSchemaNode;
import org.opendaylight.yangtools.yang.model.api.SchemaContext;
import org.opendaylight.yangtools.yang.model.api.stmt.LeafEffectiveStatement;
import org.opendaylight.yangtools.yang.model.api.stmt.LeafListEffectiveStatement;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Deque;

import static javax.xml.stream.XMLStreamConstants.END_ELEMENT;
import static javax.xml.stream.XMLStreamConstants.START_ELEMENT;

/**
 * 该类用于过滤不符合网元schema结构的消息字段
 * <p>
 * 处理逻辑与{@link org.opendaylight.yangtools.yang.data.codec.xml.XmlParserStream}类似
 *
 * @author Huafei Zhang
 */
public class UnknownNodeFilter {
    private final SchemaContext schemaContext;

    public UnknownNodeFilter(SchemaContext schemaContext) {
        this.schemaContext = schemaContext;
    }

    public DOMSource from(DOMSource domSource) throws XMLStreamException,
            URISyntaxException {
        XMLStreamReader reader = new DOMSourceXMLStreamReader(domSource);

        if (!reader.hasNext()) {
            return domSource;
        }
        reader.next();
        String xmlElementName = reader.getLocalName();
        String xmlElementNamespace = reader.getNamespaceURI();

        if (!NetconfMessageTransformUtil.NETCONF_CONFIG_QNAME.getLocalName()
                .equals(xmlElementName)) {
            throw new IllegalStateException(String.format(
                    "Expected element name 'config', but rather %s",
                    xmlElementName));
        }

        final Element element = XmlUtil.createElement(XmlUtil.newDocument(), NetconfMessageTransformUtil.NETCONF_CONFIG_QNAME.getLocalName(),
                Optional.of(NetconfMessageTransformUtil.NETCONF_CONFIG_QNAME.getNamespace().toString()));
        DOMResult result = new DOMResult(element);
        XMLStreamWriter writer = NetconfUtil.XML_FACTORY
                .createXMLStreamWriter(result);
        // 不确定是否有用
        writer.setPrefix(XMLConstants.DEFAULT_NS_PREFIX, xmlElementNamespace);
        writer.writeDefaultNamespace(xmlElementNamespace);

        ContainerSchemaNode schemaForDataWrite = createSchemaForEditConfig();
        DOMSource domSourceNoConfig = new DOMSource(domSource.getNode().getFirstChild());
        XMLStreamReader reader2 = new DOMSourceXMLStreamReader(domSourceNoConfig);
        filter(reader2, writer, schemaForDataWrite);
        writer.flush();
        return new DOMSource(result.getNode());
    }

    private void filter(XMLStreamReader reader, XMLStreamWriter writer,
                        DataSchemaNode parent) throws XMLStreamException,
            URISyntaxException {
        if (!reader.hasNext()) {
            return;
        }

        // 叶子结点的处理
        if (parent instanceof LeafEffectiveStatement
                || parent instanceof LeafListEffectiveStatement) {
            writer.writeCharacters(reader.getElementText().trim());
            writer.writeEndElement();
            if (isNextEndDocument(reader)) {
                return;
            }

            if (!isAtElement(reader)) {
                reader.nextTag();
            }
            return;
        }

        switch (reader.next()) {
            case START_ELEMENT:
                // 兄弟结点遍历
                while (reader.hasNext()) {
                    String xmlElementName = reader.getLocalName();
                    String xmlElementNamespace = reader.getNamespaceURI();
                    final String parentSchemaName = parent.getQName().getLocalName();

                    //结束标签的处理（非叶子结点）
                    if (parentSchemaName.equals(xmlElementName)
                            && reader.getEventType() == END_ELEMENT) {
                        endElement(writer);
                        if (isNextEndDocument(reader)) {
                            break;
                        }
                        if (!isAtElement(reader)) {
                            reader.nextTag();
                        }
                        break;
                    }

                    final Deque<DataSchemaNode> childDataSchemaNodes = ParserStreamUtils
                            .findSchemaNodeByNameAndNamespace(parent, xmlElementName,
                                    new URI(xmlElementNamespace));
                    // 查找结点对应shcema失败
                    if (childDataSchemaNodes.isEmpty()) {
                        if (parentSchemaName.equals("config")) {
                            throw new IllegalStateException("No matched schema for '" + xmlElementName + "' found in schema context");
                        }
                        skipUnknownNode(reader);
                        continue;
                    }
                    // 开始标签处理
                    startElement(reader, writer, xmlElementName, xmlElementNamespace);
                    DataSchemaNode child = specificChild(childDataSchemaNodes);
                    // 下一层标签处理
                    filter(reader, writer, child);
                }
                break;
            case END_ELEMENT:
                // 空结点的结束标签处理
                endElement(writer);
                if (!isAtElement(reader)) {
                    reader.nextTag();
                }
                break;
            default:
                break;
        }
    }

    private void startElement(XMLStreamReader reader, XMLStreamWriter writer, String xmlElementName, String xmlElementNamespace) throws XMLStreamException {
        if (reader.getEventType() == START_ELEMENT) {
            writer.writeStartElement(XMLConstants.DEFAULT_NS_PREFIX, xmlElementName,
                    xmlElementNamespace);

            for (int i = 0; i < reader.getNamespaceCount(); i++) {
                String namespace = reader.getNamespaceURI(i);
                String prefix = reader.getNamespacePrefix(i);
                writer.setPrefix(prefix, namespace);
            }

            for (int i = 0; i < reader.getAttributeCount(); i++) {
                String prefix = reader.getAttributePrefix(i);
                String localName = reader.getAttributeLocalName(i);
                String value = reader.getAttributeValue(i);
                String namespace = reader.getAttributeNamespace(i);
                writer.writeAttribute(prefix, namespace, localName, value);
            }
        }
    }

    private static boolean isNextEndDocument(final XMLStreamReader in) throws XMLStreamException {
        return !in.hasNext() || in.next() == XMLStreamConstants.END_DOCUMENT;
    }

    private void endElement(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeEndElement();
    }

    private ContainerSchemaNode createSchemaForEditConfig() {
        return new NodeContainerProxy(
                NetconfMessageTransformUtil.NETCONF_CONFIG_QNAME,
                schemaContext.getChildNodes());
    }

    private static void skipUnknownNode(final XMLStreamReader reader)
            throws XMLStreamException {
        int levelOfNesting = 0;
        while (reader.hasNext()) {
            reader.next();
            if (!isAtElement(reader)) {
                reader.nextTag();
            }
            if (reader.isStartElement()) {
                levelOfNesting++;
            }

            if (reader.isEndElement()) {
                if (levelOfNesting == 0) {
                    break;
                }

                levelOfNesting--;
            }
        }
        reader.nextTag();
    }

    private static boolean isAtElement(final XMLStreamReader reader) {
        return reader.getEventType() == START_ELEMENT
                || reader.getEventType() == END_ELEMENT;
    }

    /**
     * FIXME: 未处理augment
     */
    private DataSchemaNode specificChild(final Deque<DataSchemaNode> schemas) {
        Preconditions.checkArgument(!schemas.isEmpty(),
                "Expecting at least one schema");

        final DataSchemaNode schema = schemas.pop();
        if (schemas.isEmpty()) {
            return schema;
        }
        return schemas.removeLast();
    }

}
