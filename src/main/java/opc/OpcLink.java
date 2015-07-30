package opc;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.serializer.Deserializer;
import org.dsa.iot.dslink.serializer.Serializer;
import org.vertx.java.core.Handler;

public class OpcLink {
	
	private Node node;
	
	Serializer copySerializer;
	Deserializer copyDeserializer;
	
	private OpcLink(Node node, Serializer ser, Deserializer deser) {
		this.node = node;
		this.copySerializer = ser;
		this.copyDeserializer = deser;
	}
	
	public static void start(Node parent, Serializer copyser, Deserializer copydeser) {
		OpcLink opc = new OpcLink(parent, copyser, copydeser);
		opc.init();
	}
	
	private void init() {
		restoreLastSession();
		
		Action act = new Action(Permission.READ, new AddConnHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		act.addParameter(new Parameter("host", ValueType.STRING));
		act.addParameter(new Parameter("domain", ValueType.STRING, new Value("")));
		act.addParameter(new Parameter("user", ValueType.STRING, new Value("")));
		act.addParameter(new Parameter("password", ValueType.STRING, new Value("")));
		node.createChild("add connection").setAction(act).build().setSerializable(false);
		
	}
	
	private void restoreLastSession() {
		if (node.getChildren() == null) return;
		for (Node child: node.getChildren().values()) {
			Value host = child.getAttribute("host");
			Value domain = child.getAttribute("domain");
			Value user = child.getAttribute("user");
			Value pass = child.getAttribute("password");
			if (host!=null && domain!=null && user!=null && pass!=null) {
				OpcConn conn = new OpcConn(getMe(), child);
				conn.restoreLastSession();
			} else {
				node.removeChild(child);
			}
		}
		
	}

	private class AddConnHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			String host = event.getParameter("host", ValueType.STRING).getString();
			String domain = event.getParameter("domain", new Value("")).getString();
			String user = event.getParameter("user", new Value("")).getString();
			String password = event.getParameter("password", new Value("")).getString();
			
			Node child = node.createChild(name).build();
			child.setAttribute("host", new Value(host));
			child.setAttribute("domain", new Value(domain));
			child.setAttribute("user", new Value(user));
			child.setAttribute("password", new Value(password));
			
			OpcConn conn = new OpcConn(getMe(), child);
			conn.init();
		}
	}
	
	private OpcLink getMe() {
		return this;
	}

}
