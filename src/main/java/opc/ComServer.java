package opc;

import java.net.UnknownHostException;
import java.util.AbstractMap;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.jinterop.dcom.common.JIException;
import org.jinterop.dcom.core.IJIUnsigned;
import org.jinterop.dcom.core.JIArray;
import org.jinterop.dcom.core.JIVariant;
import org.openscada.opc.dcom.common.KeyedResult;
import org.openscada.opc.dcom.common.KeyedResultSet;
import org.openscada.opc.dcom.common.ResultSet;
import org.openscada.opc.dcom.da.IOPCDataCallback;
import org.openscada.opc.dcom.da.ValueData;
import org.openscada.opc.dcom.list.ClassDetails;
import org.openscada.opc.lib.common.AlreadyConnectedException;
import org.openscada.opc.lib.common.ConnectionInformation;
import org.openscada.opc.lib.common.NotConnectedException;
import org.openscada.opc.lib.da.AddFailedException;
import org.openscada.opc.lib.da.DuplicateGroupException;
import org.openscada.opc.lib.da.Group;
import org.openscada.opc.lib.da.Item;
import org.openscada.opc.lib.da.Server;
import org.openscada.opc.lib.da.browser.Branch;
import org.openscada.opc.lib.da.browser.Leaf;
import org.openscada.opc.lib.da.browser.TreeBrowser;
import org.openscada.opc.lib.list.Categories;
import org.openscada.opc.lib.list.Category;
import org.openscada.opc.lib.list.ServerList;
import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonArray;

public class ComServer extends OpcServer {
	
	private Server server;
	private Group subGroup;
	private final Map<Node, ItemWrap> subscribed = new ConcurrentHashMap<Node, ItemWrap>();
	
	ComServer(OpcConn c, Node n) {
		super(c, n);
	}
	
	@Override
	protected void connect(String host, String domain, String user, String pass) {
		String progId = node.getAttribute("server prog id").getString();
		
		ConnectionInformation ci = new ConnectionInformation();
        ci.setHost(host);
        ci.setDomain(domain);
        ci.setUser(user);
        ci.setPassword(pass);
        try {
			ci.setClsid(getClsId(host, domain, user, pass, progId));
		} catch (Exception e1) {
			LOGGER.debug("", e1);
			ci.setProgId(progId);
		}
        // create a new server
        server = new Server(ci, Executors.newSingleThreadScheduledExecutor());
	
        try {
            // connect to server
            server.connect();

            subGroup = server.addGroup();
            subGroup.attach(new ItemCallback());
            populateGroup();
            subGroup.setActive(true);
            stopped = false;
            
        } catch (final JIException e) {
        	LOGGER.debug("", e);
        	stop();
        } catch (IllegalArgumentException e) {
        	LOGGER.debug("", e);
        	stop();
		} catch (UnknownHostException e) {
			LOGGER.debug("", e);
			stop();
		} catch (NotConnectedException e) {
			LOGGER.debug("", e);
			stop();
		} catch (DuplicateGroupException e) {
			LOGGER.debug("", e);
			stop();
		} catch (AlreadyConnectedException e) {
			LOGGER.debug("", e);
		}
	}
	
	@Override
	protected void onConnected() {
		try {
			buildTree();
		} catch (IllegalArgumentException e) {
			LOGGER.debug("", e);
		} catch (UnknownHostException e) {
			LOGGER.debug("", e);
		} catch (JIException e) {
			LOGGER.debug("", e);
		}
	}

	@Override
	protected Action getEditAction(String host, String domain, String user, String pass) {
		String progId = node.getAttribute("server prog id").getString();
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));
		Set<String> enums = null;
		try {
			enums = OpcConn.listOPCServers(user, pass, host, domain);
		} catch (Exception e) {
			LOGGER.debug("", e);
		}
		if (enums != null && enums.size() > 0) {
			if (enums.contains(progId)) {
				act.addParameter(new Parameter("server prog id", ValueType.makeEnum(enums), new Value(progId)));
				act.addParameter(new Parameter("server prog id (manual entry)", ValueType.STRING));
			} else {
				act.addParameter(new Parameter("server prog id", ValueType.makeEnum(enums)));
				act.addParameter(new Parameter("server prog id (manual entry)", ValueType.STRING, new Value(progId)));
			}
		} else {
			act.addParameter(new Parameter("server prog id", ValueType.STRING, new Value(progId)));
		}
//		double defint = interval / 1000.0;
//		act.addParameter(new Parameter("polling interval", ValueType.NUMBER, new Value(defint)));
		return act;
	}

	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			String progId;
			Value customId = event.getParameter("server prog id (manual entry)");
			if (customId != null && customId.getString() != null && customId.getString().trim().length() > 0) {
				progId = customId.getString();
			} else {
				progId = event.getParameter("server prog id").getString();
			}
//			int interval = (int) (event.getParameter("polling interval", ValueType.NUMBER).getNumber().doubleValue()*1000);
			
			if (name!=null && name.length()>0 && !name.equals(node.getName())) {
				Node newNode = node.getParent().createChild(name).build();
				newNode.setAttribute("server prog id", new Value(progId));
				ComServer os = new ComServer(conn, newNode);
				remove();
				os.restoreLastSession();
			} else {
			
				node.setAttribute("server prog id", new Value(progId));
//				node.setAttribute("polling interval", new Value(interval));
			
				stop();
				init();
			}
		}
	}
	
	@Override
	protected void stop() {
		if (subGroup != null) {
			try {
				subGroup.setActive(false);
			} catch (JIException e) {
				LOGGER.debug("", e);
			}
		}
		if (server != null) {
			server.disconnect();
			server = null;
		}
		
		super.stop();
	}
	
	private static class ItemWrap {
		private Item item;
		ItemWrap(Item i) {
			item = i;
		}
		Item get() {
			return item;
		}
	}
	
	private void buildTree() throws IllegalArgumentException, UnknownHostException, JIException {
		final TreeBrowser treeBrowser = server.getTreeBrowser();
        if ( treeBrowser != null )
        {
            dumpTree(treeBrowser.browse(), node);
        }
	}
	
	private void dumpTree (final Branch branch, Node branchNode) {
		
        for (final Leaf leaf : branch.getLeaves()) {
            Node child = branchNode.createChild(leaf.getName()).setValueType(ValueType.STRING).build();
            child.setAttribute("item id", new Value(leaf.getItemId()));
            child.setAttribute("accessRights", new Value("readWritable"));
            setupNode(child);
        }
        for (final Branch subBranch : branch.getBranches()) {
            Node child = branchNode.createChild(subBranch.getName()).build();
        	if (LAZY_LOAD) {
        		child.getListener().setOnListHandler(new Handler<Node>() {
        			private boolean loaded = false;
        			public void handle(Node event) {
        				if (!loaded) dumpTree(subBranch, event);
        				loaded = true;
        			}
        		});
        	} else {
        		dumpTree(subBranch, child);
        	}
        }
    }
	
	public static String getClsId(String host, String domain, String user, String password,  String progId)
            throws IllegalArgumentException, UnknownHostException, JIException {

        ServerList serverListOPC = new ServerList(host, user, password, domain);

        Collection<ClassDetails> detailsList = serverListOPC.listServersWithDetails(
                new Category[] { Categories.OPCDAServer20 }, new Category[] { Categories.OPCDAServer10 });

        for (ClassDetails classDetails : detailsList) {
            if (progId.equals(classDetails.getProgId())) {
                return classDetails.getClsId();
            }
        }

        return null;
    }
	
	
	
	private static JIVariant getJIFromValue(Value val) {
		if (val == null) {
			return null;
		}
		if (val.getType().compare(ValueType.NUMBER))  {
			return new JIVariant(val.getNumber().doubleValue());
		} else if (val.getType().compare(ValueType.STRING)) {
			return new JIVariant(val.getString());
		} else if (val.getType().compare(ValueType.BOOL)) {
			return new JIVariant(val.getBool());
		} else if (val.getType().compare(ValueType.ARRAY)) {
			JsonArray jsonArr = val.getArray();
			JIVariant[] arr = new JIVariant[jsonArr.size()];
			for (int i=0;i<jsonArr.size();i++) {
				Object o = jsonArr.get(i);
				Value v;
				if (o instanceof Number) v = new Value((Number) o);
				else if (o instanceof Boolean) v = new Value((Boolean) o);
				else if (o instanceof String) v = new Value((String) o);
				else if (o instanceof JsonArray) v = new Value((JsonArray) o);
				else v = new Value(o.toString());
				arr[i] = getJIFromValue(v);
			}
			return new JIVariant(new JIArray(arr));
		} else {
			return null;
		}
	}
	
	@Override
	protected Handler<ValuePair> getSetHandler(Node child) {
		return new SetHandler(child);
	}
	
	protected class SetHandler implements Handler<ValuePair> {
		private Node itemNode;
		SetHandler(Node n) {
			itemNode = n;
		}
		public void handle(ValuePair event) {
			if (!event.isFromExternalSource()) return;
			ItemWrap itemW = subscribed.get(itemNode);
			if (itemW == null) return;
			Item item = itemW.get();
			LOGGER.debug("writing to item with id: " + item.getId());
			Value newVal = event.getCurrent();
			
			JIVariant ji = getJIFromValue(newVal);
			if (ji == null) return;
			try {
				item.write(ji);
			} catch (JIException e) {
				LOGGER.debug("", e);
			}
		}
	}
	
	private static Entry<ValueType, Value> getValueFromJI(JIVariant ji) throws JIException {
		Value val;
		ValueType vt;
		int type = ji.getType();
		switch (type) {
		case JIVariant.VT_NULL:
		case JIVariant.VT_EMPTY: {
			vt = ValueType.STRING;
			val = null;
			break;
		}
		case JIVariant.VT_INT:
		case JIVariant.VT_I4: {
			vt = ValueType.NUMBER;
			val = new Value(ji.getObjectAsInt());
			break;
		}
		case JIVariant.VT_I2: {
			vt = ValueType.NUMBER;
			val = new Value(ji.getObjectAsShort());
			break;
		}
		case JIVariant.VT_I1: {
			vt = ValueType.NUMBER;
			val = new Value((byte) ji.getObjectAsChar());
			break;
		}
		case JIVariant.VT_I8:  {
			vt = ValueType.NUMBER;
			val = new Value(ji.getObjectAsLong());
			break;
		}
		case JIVariant.VT_UINT:
		case JIVariant.VT_UI4:
		case JIVariant.VT_UI2:
		case JIVariant.VT_UI1: {
			vt = ValueType.NUMBER;
			val = new Value(ji.getObjectAsUnsigned().getValue());
			break;
		}
		case JIVariant.VT_R4: {
			vt = ValueType.NUMBER;
			val = new Value(ji.getObjectAsFloat());
			break;
		}
		case JIVariant.VT_DECIMAL:
		case JIVariant.VT_R8: {
			vt = ValueType.NUMBER;
			val = new Value(ji.getObjectAsDouble());
			break;
		}
		case JIVariant.VT_BOOL: {
			vt = ValueType.BOOL;
			val = new Value(ji.getObjectAsBoolean());
			break;
		}
		case JIVariant.VT_DATE: {
			vt = ValueType.STRING;
			val = new Value(ji.getObjectAsDate().toString());
			break;
		}
		case JIVariant.VT_BSTR: {
			vt = ValueType.STRING;
			val = new Value(ji.getObjectAsString2());
			break;
		}
		case JIVariant.VT_ERROR: {
			vt = ValueType.STRING;
			val = new Value(ji.getObjectAsSCODE());
			break;
		}
		default: {
			if ((type & 0xf000) == JIVariant.VT_ARRAY) {
				vt = ValueType.ARRAY;
				JsonArray jsonArr = new JsonArray();
				Object[] objArr = (Object[]) ji.getObjectAsArray().getArrayInstance();
				for (Object o: objArr) {
					if (o instanceof Number) jsonArr.addNumber((Number) o);
					else if (o instanceof String) jsonArr.addString((String) o);
					else if (o instanceof Date) jsonArr.addString(((Date) o).toString());
					else if (o instanceof Boolean) jsonArr.addBoolean((Boolean) o);
					else {
						Value v;
						ValueType vtype;
						try {
							JIVariant oji;
							if (o instanceof JIVariant) oji = (JIVariant) o;
							else if (o instanceof IJIUnsigned) oji = new JIVariant((IJIUnsigned) o);
							else oji = JIVariant.makeVariant(o);
							Entry<ValueType, Value> valent = getValueFromJI(oji);
							v = valent.getValue();
							vtype = valent.getKey();
						} catch (Exception e) {
							vtype = ValueType.STRING;
							v = new Value(o.toString());
						}
						if (ValueType.ARRAY.compare(vtype)) jsonArr.addArray(v.getArray());
						else if (ValueType.BOOL.compare(vtype)) jsonArr.addBoolean(v.getBool());
						else if (ValueType.NUMBER.compare(vtype)) jsonArr.addNumber(v.getNumber());
						else jsonArr.addString(v.getString());
					}
				}
				val = new Value(jsonArr);
			} else {
				vt = ValueType.STRING;
				val = new Value(ji.toString());
			}
		}
		}
		return new AbstractMap.SimpleImmutableEntry<ValueType, Value>(vt, val);
	}
	
	private class ItemCallback implements IOPCDataCallback {
		private void changed(Item item, ValueData data, Node itemNode) throws JIException {
				
				JIVariant ji = data.getValue();
				
				Entry<ValueType, Value> entry = getValueFromJI(ji);
				
				itemNode.setValueType(entry.getKey());
				itemNode.setValue(entry.getValue());

	    }

		public void dataChange(int transactionId, int serverGroupHandle, int masterQuality, int masterErrorCode, KeyedResultSet<Integer, ValueData> result) {
			for (KeyedResult<Integer, ValueData> kr: result) {
				Item item = subGroup.findItemByClientHandle(kr.getKey());
				if (item == null) return;
				Node itemNode = itemNodes.get(item.getId());
				try {
					changed(item, kr.getValue(), itemNode);
				} catch (JIException e) {
					LOGGER.debug("", e);
				}
				
			}
			
		}

		public void readComplete(int transactionId, int serverGroupHandle, int masterQuality, int masterErrorCode, KeyedResultSet<Integer, ValueData> result) {
			for (KeyedResult<Integer, ValueData> kr: result) {
				Item item = subGroup.findItemByClientHandle(kr.getKey());
				if (item == null) return;
				Node itemNode = itemNodes.get(item.getId());
				try {
					changed(item, kr.getValue(), itemNode);
				} catch (JIException e) {
					LOGGER.debug("", e);
				}
				
			}
			
		}

		public void writeComplete(int transactionId, int serverGroupHandle, int masterErrorCode, ResultSet<Integer> result) {
			// TODO Auto-generated method stub
			
		}

		public void cancelComplete(int transactionId, int serverGroupHandle) {
			// TODO Auto-generated method stub
			
		}
	}

	public void addItemSub(Node event) {
		if (subscribed.containsKey(event)) return;
		String itemId = event.getAttribute("item id").getString();
		if (subGroup != null) {
			try {
				subGroup.validateItems(itemId);
				Item item = subGroup.addItem(itemId);
				item.read(true);
				subscribed.put(event, new ItemWrap(item));
			} catch (JIException e) {
				LOGGER.debug("", e);
			} catch (AddFailedException e) {
				LOGGER.debug("", e);
			}
		}
		if (!subscribed.containsKey(event)) {
			subscribed.put(event, new ItemWrap(null));
		}
		
	}

	public void removeItemSub(Node event) {
		subscribed.remove(event);
		String itemId = event.getAttribute("item id").getString();
//		if (access != null) access.removeItem(itemId);
		if (subGroup != null)
			try {
				subGroup.removeItem(itemId);
			} catch (IllegalArgumentException e) {
				LOGGER.debug("", e);
			} catch (UnknownHostException e) {
				LOGGER.debug("", e);
			} catch (JIException e) {
				LOGGER.debug("", e);
			}
		
	}
	
	private void populateGroup() {
		if (subGroup == null) return;
		for (Node n: subscribed.keySet()) {
			String itemId = n.getAttribute("item id").getString();
			try {
				subGroup.validateItems(itemId);
				Item i = subGroup.addItem(itemId);
				subscribed.put(n, new ItemWrap(i));
			} catch (JIException e) {
				LOGGER.debug("", e);
			} catch (AddFailedException e) {
				LOGGER.debug("", e);
			}
		}
	}
}
