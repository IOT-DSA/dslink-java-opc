package opc;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.EditorType;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.json.JsonObject;
import org.openscada.opc.dcom.list.ClassDetails;
import org.openscada.opc.lib.list.Categories;
import org.openscada.opc.lib.list.Category;
import org.openscada.opc.lib.list.ServerList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.dsa.iot.dslink.util.handler.Handler;

public class OpcConn {
	private static final Logger LOGGER;
	static {
		LOGGER = LoggerFactory.getLogger(OpcConn.class);
	}
	
	final OpcLink link;
	final Node node;
	
	OpcConn(OpcLink link, Node node) {
		this.link = link;
		this.node = node;
		
		Action act = new Action(Permission.READ, new RemoveHandler());
		node.createChild("remove").setAction(act).build().setSerializable(false);
	}
	
	void init() {
		
		String host = node.getAttribute("host").getString();
		String domain = node.getAttribute("domain").getString();
		String user = node.getAttribute("user").getString();
		String pass = node.getAttribute("password").getString();
		
		Action act = getEditAction(host, domain, user, pass);
		Node anode = node.getChild("edit");
		if (anode == null) node.createChild("edit").setAction(act).build().setSerializable(false);
		else anode.setAction(act);
		
		act = new Action(Permission.READ, new RefreshHandler());
		anode = node.getChild("refresh");
		if (anode == null) node.createChild("refresh").setAction(act).build().setSerializable(false);
		else anode.setAction(act);
		
		act = getAddServerAction(host, domain, user, pass);
		anode = node.getChild("add server");
		if (anode == null) node.createChild("add server").setAction(act).build().setSerializable(false);
		else anode.setAction(act);
		
		act = new Action(Permission.READ, new AddXmlHandler());
		act.addParameter(new Parameter("name", ValueType.STRING, new Value("OpcXmlDaSrv")));
		act.addParameter(new Parameter("url", ValueType.STRING, new Value("http://advosol.com/XMLDADemo/ts_sim/OpcDaGateway.asmx")));
		act.addParameter(new Parameter("service name", ValueType.STRING, new Value("OpcXmlDaSrv")));
		anode = node.getChild("add xml-da server");
		if (anode == null) node.createChild("add xml-da server").setAction(act).build().setSerializable(false);
		else anode.setAction(act);
	}
	
	private Action getAddServerAction(String host, String domain, String user, String pass) {
		Action act = new Action(Permission.READ, new AddServerHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		Set<String> enums = null;
		try {
			enums = listOPCServers(user, pass, host, domain);
		} catch (Exception e) {
			LOGGER.debug("", e);
		}
		if (enums != null && enums.size() > 0) {
			act.addParameter(new Parameter("server prog id", ValueType.makeEnum(enums)));
			act.addParameter(new Parameter("server prog id (manual entry)", ValueType.STRING).setPlaceHolder("Matrikon.OPC.Simulation.1"));
		} else {
			act.addParameter(new Parameter("server prog id", ValueType.STRING).setPlaceHolder("Matrikon.OPC.Simulation.1"));
		}
		act.addParameter(new Parameter("polling interval", ValueType.NUMBER, new Value(1)).setDescription("set this to 0 for subscription"));
		return act;
	}
	
	private Action getEditAction(String host, String domain, String user, String pass) {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));
		act.addParameter(new Parameter("host", ValueType.STRING, new Value(host)));
		act.addParameter(new Parameter("domain", ValueType.STRING, new Value(domain)));
		act.addParameter(new Parameter("user", ValueType.STRING, new Value(user)));
		act.addParameter(new Parameter("password", ValueType.STRING, new Value(pass)).setEditorType(EditorType.PASSWORD));
		return act;
	}
	
	private class RemoveHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			remove();
		}
	}
	
	private void remove() {
		node.clearChildren();
		node.getParent().removeChild(node);
	}
	
	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			String host = event.getParameter("host", ValueType.STRING).getString();
			String domain = event.getParameter("domain", ValueType.STRING).getString();
			String user = event.getParameter("user", ValueType.STRING).getString();
			String password = event.getParameter("password", ValueType.STRING).getString();
			
			node.setAttribute("host", new Value(host));
			node.setAttribute("domain", new Value(domain));
			node.setAttribute("user", new Value(user));
			node.setAttribute("password", new Value(password));
			
			if (name != null && name.length()>0 && !name.equals(node.getName())) {
				rename(name);
			} else {
				init();
			}
		}
	}
	
	private void rename(String name) {
		JsonObject jobj = link.copySerializer.serialize();
		JsonObject parentobj = jobj;
		JsonObject nodeobj = parentobj.get(node.getName());
		parentobj.put(name, nodeobj);
		link.copyDeserializer.deserialize(jobj);
		Node newnode = node.getParent().getChild(name);
		OpcConn oc = new OpcConn(link, newnode);
		remove();
		oc.restoreLastSession();
	}
	
	private class RefreshHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			init();
		}
	}
	
	private class AddServerHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			String progId;
			Value customId = event.getParameter("server prog id (manual entry)");
			if (customId != null && customId.getString() != null && customId.getString().trim().length() > 0) {
				progId = customId.getString();
			} else {
				progId = event.getParameter("server prog id").getString();
			}
			double interval = event.getParameter("polling interval", ValueType.NUMBER).getNumber().doubleValue();
			
			Node child = node.createChild(name).build();
			child.setAttribute("server prog id", new Value(progId));
			child.setAttribute("polling interval", new Value(interval));
			ComServer os = new ComServer(getMe(), child);
			os.init();
		}
	}
	
	private class AddXmlHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			String url = event.getParameter("url", ValueType.STRING).getString();
			String servname = event.getParameter("service name", ValueType.STRING).getString();
			Node child = node.createChild(name).build();
			child.setAttribute("url", new Value(url));
			child.setAttribute("service name", new Value(servname));
			XmlServer os = new XmlServer(getMe(), child);
			os.init();
		}
	}
	
	public void restoreLastSession() {
		init();
		if (node.getChildren() == null) return;
		for (Node child: node.getChildren().values()) {
			Value progId = child.getAttribute("server prog id");
			Value url = child.getAttribute("url");
			Value service  = child.getAttribute("service name");
			Value interval = child.getAttribute("polling interval");
			if (progId != null && interval != null) {
				ComServer os = new ComServer(getMe(), child);
				os.restoreLastSession();
			} else if (url != null && service != null) {
				XmlServer os = new XmlServer(getMe(), child);
				os.restoreLastSession();
			} else if (child.getAction() == null) {
				node.removeChild(child);
			}
		}
		
	}
	
	public static Set<String> listOPCServers(String user, String password, String host, String domain)
            throws Exception {

        Set<String> listNameOPCServers = new HashSet<String>();

        ServerList serverList = new ServerList(host, user, password, domain);

        Collection<ClassDetails> detailsList = serverList.listServersWithDetails(
                new Category[] { Categories.OPCDAServer20 }, new Category[] { Categories.OPCDAServer10 });

        for (ClassDetails details : detailsList) {
            listNameOPCServers.add(details.getProgId());
        }

        return listNameOPCServers;
    }
	
    public OpcConn getMe() {
		return this;
	}

}
