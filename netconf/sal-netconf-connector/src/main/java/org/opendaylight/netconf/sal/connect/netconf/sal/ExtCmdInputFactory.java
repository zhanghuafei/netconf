package org.opendaylight.netconf.sal.connect.netconf.sal;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import org.opendaylight.netconf.api.xml.XmlUtil;
import org.opendaylight.yangtools.yang.common.QName;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.AnyXmlNode;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.data.impl.schema.Builders;
import org.opendaylight.yangtools.yang.data.impl.schema.builder.api.NormalizedNodeAttrBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.transform.dom.DOMSource;
import java.util.Map;

import static org.opendaylight.netconf.sal.connect.netconf.sal.NetconfPagingService.*;

public class ExtCmdInputFactory {
    public static final QName UTSTARCOM_EXT =
            QName.create("urn.utstar:uar:ExtCmd", "2017-11-02", "utstarcom-ext").intern();

    public static final QName COMMAND = QName.create(UTSTARCOM_EXT, "Command");
    public static final QName EXT_CMD_RPC_QNAME = QName.create(UTSTARCOM_EXT, "extcmd");

    public static AnyXmlNode createExtCmdInput(String moduleName, TableType type) {
        Preconditions.checkNotNull(type);
        final NormalizedNodeAttrBuilder<YangInstanceIdentifier.NodeIdentifier, DOMSource, AnyXmlNode> anyXmlBuilder =
                Builders.anyXmlBuilder().withNodeIdentifier(new YangInstanceIdentifier.NodeIdentifier(
                        QName.create(UTSTARCOM_EXT, COMMAND.getLocalName()).intern()));
        Map<QName, String> attributes = Maps.newHashMap();
        anyXmlBuilder.withAttributes(attributes);

        Document doc = XmlUtil.newDocument();
        final Element element =
                doc.createElementNS(COMMAND.getNamespace().toString(), COMMAND.getLocalName());

        element.appendChild(createExtCmds(doc, moduleName, type));
        anyXmlBuilder.withValue(new DOMSource(element));
        return anyXmlBuilder.build();
    }

    private static Node createExtCmds(Document doc, String moduleName, TableType type) {
        Element extCmds = doc.createElement("ExtCmds");
        extCmds.appendChild(createExtCmd(doc, moduleName, type));
        return extCmds;
    }

    private static Node createExtCmd(Document doc, String moduleName, TableType type) {
        Element extCmd = doc.createElement("ExtCmd");

        Element index = doc.createElement("Index");
        index.appendChild(doc.createTextNode("1"));

        Element cmdName = doc.createElement("CmdName");
        cmdName.appendChild(doc.createTextNode("queryCnt"));

        Element operation = doc.createElement("Operation");
        operation.appendChild(doc.createTextNode("execute"));

        Element timeout = doc.createElement("Timeout");
        timeout.appendChild(doc.createTextNode("10"));

        Element sync = doc.createElement("Sync");
        sync.appendChild(doc.createTextNode("1"));

        String paraValue = String.format("{{\"DsName\",{String,\"%s\"}},{\"TblName\",{String,\"%s\"}}}", type.toString(), moduleName);
        Element params = doc.createElement("Params");
        params.appendChild(doc.createTextNode(paraValue));

        extCmd.appendChild(index);
        extCmd.appendChild(cmdName);
        extCmd.appendChild(operation);
        extCmd.appendChild(timeout);
        extCmd.appendChild(sync);
        extCmd.appendChild(params);

        return extCmd;
    }

}
