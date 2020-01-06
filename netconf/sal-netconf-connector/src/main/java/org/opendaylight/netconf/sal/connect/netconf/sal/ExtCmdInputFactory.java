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
import org.opendaylight.yangtools.yang.model.api.Module;
import org.opendaylight.yangtools.yang.model.api.RpcDefinition;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.transform.dom.DOMSource;
import java.util.Map;

import static org.opendaylight.netconf.sal.connect.netconf.sal.NetconfPagingService.*;

public class ExtCmdInputFactory {
    public final QName moduleQname;
    public final QName command;
    public final QName extCmdRpcName;

    public ExtCmdInputFactory(Module extCmdModule) {
        moduleQname = QName.create(extCmdModule.getNamespace().toString(), extCmdModule.getRevision().get().toString(), extCmdModule.getName()).intern();
        command = QName.create(moduleQname, "Command");
        extCmdRpcName = QName.create(moduleQname, "extcmd");
    }

    public AnyXmlNode createExtCmdInput(Long indexValue, String cmdNameValue, String operationValue, Integer timeoutValue, Integer syncValue, String paraValue) {
        final NormalizedNodeAttrBuilder<YangInstanceIdentifier.NodeIdentifier, DOMSource, AnyXmlNode> anyXmlBuilder =
                Builders.anyXmlBuilder().withNodeIdentifier(new YangInstanceIdentifier.NodeIdentifier(
                        QName.create(moduleQname, command.getLocalName()).intern()));

        Document doc = XmlUtil.newDocument();
        final Element element =
                doc.createElementNS(command.getNamespace().toString(), command.getLocalName());

        element.appendChild(createExtCmds(doc, indexValue, cmdNameValue, operationValue, timeoutValue, syncValue, paraValue));
        anyXmlBuilder.withValue(new DOMSource(element));
        return anyXmlBuilder.build();
    }

    private static Element createExtCmds(Document doc, Long indexValue, String cmdNameValue, String operationValue, Integer timeoutValue, Integer syncValue, String paraValue) {
        Element extCmds = doc.createElement("ExtCmds");
        Element extCmd = createExtCmd(doc, indexValue, cmdNameValue, operationValue, timeoutValue, syncValue, paraValue);
        extCmds.appendChild(extCmd);
        return extCmds;
    }

    private static Element createExtCmd(Document doc, Long indexValue, String cmdNameValue, String operationValue, Integer timeoutValue, Integer syncValue, String paraValue) {
        Element extCmd = doc.createElement("ExtCmd");

        Element index = doc.createElement("Index");
        if (indexValue != null) {
            index.appendChild(doc.createTextNode(String.valueOf(indexValue)));
            extCmd.appendChild(index);
        }

        Element cmdName = doc.createElement("CmdName");
        if (cmdNameValue != null) {
            cmdName.appendChild(doc.createTextNode(cmdNameValue));
            extCmd.appendChild(cmdName);
        }

        Element operation = doc.createElement("Operation");
        if (operationValue != null) {
            operation.appendChild(doc.createTextNode(operationValue));
            extCmd.appendChild(operation);
        }

        Element timeout = doc.createElement("Timeout");
        if (timeoutValue != null) {
            timeout.appendChild(doc.createTextNode(String.valueOf(timeoutValue)));
            extCmd.appendChild(timeout);
        }

        Element sync = doc.createElement("Sync");
        if (syncValue != null) {
            sync.appendChild(doc.createTextNode(String.valueOf(syncValue)));
            extCmd.appendChild(sync);
        }

        Element params = doc.createElement("Params");
        if (paraValue != null) {
            params.appendChild(doc.createTextNode(paraValue));
            extCmd.appendChild(params);
        }

        return extCmd;
    }
}
