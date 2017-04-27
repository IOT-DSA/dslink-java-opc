package opc;

import java.net.UnknownHostException;
import java.text.DateFormat;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.AbstractMap;
import java.util.Calendar;
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
import org.dsa.iot.dslink.util.json.JsonArray;
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
import org.openscada.opc.lib.da.AccessBase;
import org.openscada.opc.lib.da.AddFailedException;
import org.openscada.opc.lib.da.DataCallback;
import org.openscada.opc.lib.da.DuplicateGroupException;
import org.openscada.opc.lib.da.Group;
import org.openscada.opc.lib.da.Item;
import org.openscada.opc.lib.da.ItemState;
import org.openscada.opc.lib.da.Server;
import org.openscada.opc.lib.da.SyncAccess;
import org.openscada.opc.lib.da.browser.Branch;
import org.openscada.opc.lib.da.browser.FlatBrowser;
import org.openscada.opc.lib.da.browser.Leaf;
import org.openscada.opc.lib.da.browser.TreeBrowser;
import org.openscada.opc.lib.list.Categories;
import org.openscada.opc.lib.list.Category;
import org.openscada.opc.lib.list.ServerList;
import org.dsa.iot.dslink.util.handler.Handler;

public class ComServer extends OpcServer {
	
	private Server server;
//	private AutoReconnectController autoReconnectController = null;
	private Group subGroup;
	private AccessBase access;
	final private SyncItemCallback callbackSync = new SyncItemCallback();
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
        Value clsidVal = node.getAttribute("server cls id");
        if (clsidVal != null) {
        	ci.setClsid(clsidVal.getString());
        } else {
	        try {
				ci.setClsid(getClsId(host, domain, user, pass, progId));
			} catch (Exception e1) {
				LOGGER.debug("", e1);
				ci.setProgId(progId);
			}
        }
        // create a new server
        server = new Server(ci, Executors.newSingleThreadScheduledExecutor());
        
        boolean success = false;
        
        try {
            // connect to server
            server.connect();
        	success = true;
            
        } catch (final JIException e) {
        	LOGGER.debug("", e);
        	success = false;
        } catch (IllegalArgumentException e) {
        	LOGGER.debug("", e);
        	success = false;
		} catch (UnknownHostException e) {
			LOGGER.debug("", e);
			success = false;
		} catch (AlreadyConnectedException e) {
			LOGGER.debug("", e);
		} catch (Exception e) {
			LOGGER.debug("", e);
			success = false;
		}
        
        if (!success) {
        	ci = new ConnectionInformation();
            ci.setHost(host);
            ci.setDomain(domain);
            ci.setUser(user);
            ci.setPassword(pass);
            ci.setProgId(progId);
            server = new Server(ci, Executors.newSingleThreadScheduledExecutor());
            try {
				server.connect();
				success = true;
			} catch (IllegalArgumentException e) {
				LOGGER.debug("", e);
				success = false;
			} catch (UnknownHostException e) {
				LOGGER.debug("", e);
				success = false;
			} catch (JIException e) {
				LOGGER.debug("", e);
				success = false;
			} catch (AlreadyConnectedException e) {
				LOGGER.debug("", e);
			} catch (Exception e) {
				LOGGER.debug("", e);
				success = false;
			}
        }
        
        if (success) {
        	try {
        		double interv = node.getAttribute("polling interval").getNumber().doubleValue();
        		if (interv == 0) {
        			access = null;
        			subGroup = server.addGroup();
        			subGroup.attach(new ItemCallback());
        			populateGroup();
        			subGroup.setActive(true);
        		} else {
        			subGroup = server.addGroup();
        			populateGroup();
        			access = new SyncAccess(server, (int) (interv*1000));
        			populateAccess();
        			access.bind();
        		}
	        	stopped = false;
			} catch (IllegalArgumentException e) {
				LOGGER.debug("", e);
				stop();
			} catch (UnknownHostException e) {
				LOGGER.debug("", e);
				stop();
			} catch (NotConnectedException e) {
				LOGGER.debug("", e);
				stop();
			} catch (JIException e) {
				LOGGER.debug("", e);
				stop();
			} catch (DuplicateGroupException e) {
				LOGGER.debug("", e);
				stop();
			}
        } else {
        	stop();
        }
	}
	
	@Override
	protected void onConnected() {
		try {
			if (node.getAttribute("discover").getBool()) buildTree();
			else {
				Action act = new Action(Permission.READ, new AddItemHandler());
				act.addParameter(new Parameter("item id", ValueType.STRING));
				Node anode = node.getChild("add item", true);
				if (anode == null) node.createChild("add item", true).setAction(act).setSerializable(false).build();
				else anode.setAction(act);
			}
		} catch (Exception e) {
			LOGGER.warn(node.getName() + ": error during discovery");
			LOGGER.debug("", e);
		}
//		Value intval = node.getAttribute("refresh interval");
//		if (intval == null) return;
//		long interv = intval.getNumber().longValue();
//		if (interv <= 0) return;
//		ScheduledThreadPoolExecutor stpe = Objects.getDaemonThreadPool();
//		stpe.schedule(new Runnable() {
//			public void run() {
//				stop();
//				init();
//			}
//		}, interv, TimeUnit.MINUTES);
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

		act.addParameter(new Parameter("server cls id (manual entry)", ValueType.STRING, node.getAttribute("server cls id")));
		act.addParameter(new Parameter("polling interval", ValueType.NUMBER, node.getAttribute("polling interval")).setDescription("Polling interval in seconds. Set this to 0 for subscription."));
		act.addParameter(new Parameter("discover", ValueType.BOOL, node.getAttribute("discover")));
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
			Value clsid = event.getParameter("server cls id (manual entry)");
			double interval = event.getParameter("polling interval", ValueType.NUMBER).getNumber().doubleValue();
			boolean disc = event.getParameter("discover", ValueType.BOOL).getBool();
			
			if (name!=null && name.length()>0 && !name.equals(node.getName())) {
				Node newNode = node.getParent().createChild(name, true).build();
				newNode.setAttribute("server prog id", new Value(progId));
				if (clsid != null && clsid.getString() != null && clsid.getString().length()>0) newNode.setAttribute("server cls id", clsid);
				newNode.setAttribute("polling interval", new Value(interval));
				newNode.setAttribute("discover", new Value(disc));
				ComServer os = new ComServer(conn, newNode);
				remove();
				os.restoreLastSession();
			} else {
			
				node.setAttribute("server prog id", new Value(progId));
				if (clsid != null && clsid.getString() != null && clsid.getString().length()>0) node.setAttribute("server cls id", clsid);
				else node.removeAttribute("server cls id");
				node.setAttribute("polling interval", new Value(interval));
				node.setAttribute("discover", new Value(disc));
			
				stop();
				init();
			}
		}
	}
	
	private class AddItemHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String itemId = event.getParameter("item id", ValueType.STRING).getString();
			Node lvlNode = node;
			for (String lvl: itemId.split("\\.")) {
				Node ln = lvlNode.getChild(lvl, true);
				if (ln != null) lvlNode = ln;
				else lvlNode = lvlNode.createChild(lvl, true).build();
			}
			lvlNode.setValueType(ValueType.STRING);
			lvlNode.setAttribute("item id", new Value(itemId));
			lvlNode.setAttribute("accessRights", new Value("readWritable"));
			setupNode(lvlNode);
            if (node.getLink().getSubscriptionManager().hasValueSub(lvlNode)) addItemSub(lvlNode);
		}
	}
	
	@Override
	protected void stop() {
		
		subscribed.clear();
		
		if (subGroup != null) {
			try {
				subGroup.setActive(false);
				subGroup = null;
			} catch (JIException e) {
				LOGGER.debug("", e);
			}
		}
		
		if (access != null) {
			try {
				access.unbind();
				access = null;
			} catch (JIException e) {
				LOGGER.debug("", e);
			}
		}
		
//		if (autoReconnectController != null) {
//			autoReconnectController.disconnect();
//			autoReconnectController = null;
//		}
		if (server != null) {
			server.disconnect();
			server = null;
		}
		
		node.removeChild("add item", true);
		
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
        if ( treeBrowser != null ) {
            dumpTree(treeBrowser.browse(), node);
        } else {
        	LOGGER.info(node.getName() + ": Hierarchical browsing not supported, trying flat browsing");
        	final FlatBrowser flatBrowser = server.getFlatBrowser();
        	if (flatBrowser != null) {
        		dumpFlatTree(flatBrowser.browse());
        	} else {
        		LOGGER.warn(node.getName() + ": auto discovery not supported");
        	}
        }
	}
	
	private void dumpTree (final Branch branch, Node branchNode) {
		
        for (final Leaf leaf : branch.getLeaves()) {
            Node child = branchNode.createChild(leaf.getName(), true).setValueType(ValueType.STRING).build();
            child.setAttribute("item id", new Value(leaf.getItemId()));
            child.setAttribute("accessRights", new Value("readWritable"));
            setupNode(child);
            if (node.getLink().getSubscriptionManager().hasValueSub(child)) addItemSub(child);
        }
        for (final Branch subBranch : branch.getBranches()) {
            Node child = branchNode.createChild(subBranch.getName(), true).build();
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
	
	private void dumpFlatTree(Collection<String> tags) {
		for (String tag: tags) {
			Node child = node.createChild(tag, true).setValueType(ValueType.STRING).build();
            child.setAttribute("item id", new Value(tag));
            child.setAttribute("accessRights", new Value("readWritable"));
            setupNode(child);
            if (node.getLink().getSubscriptionManager().hasValueSub(child)) addItemSub(child);
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
					if (o instanceof Number) jsonArr.add(o);
					else if (o instanceof String) jsonArr.add(o);
					else if (o instanceof Date) jsonArr.add(o.toString());
					else if (o instanceof Boolean) jsonArr.add(o);
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
						if (ValueType.ARRAY.compare(vtype)) jsonArr.add(v.getArray());
						else if (ValueType.BOOL.compare(vtype)) jsonArr.add(v.getBool());
						else if (ValueType.NUMBER.compare(vtype)) jsonArr.add(v.getNumber());
						else jsonArr.add(v.getString());
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
				
				short quality = data.getQuality();
				
				itemNode.setAttribute("quality", new Value(quality));
				String qualityString = Utils.qualityCodes.get(quality);
				if (qualityString == null) {
					qualityString = "";
				}
				itemNode.setAttribute("qualityString", new Value(qualityString));
				Calendar ts = data.getTimestamp();
				DateFormat dateFormat = new W3CDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
				itemNode.setAttribute("timestamp", new Value(dateFormat.format(ts.getTime())));
				itemNode.setValueType(entry.getKey());
				itemNode.setValue(entry.getValue());
	    }

		public void dataChange(int transactionId, int serverGroupHandle, int masterQuality, int masterErrorCode, KeyedResultSet<Integer, ValueData> result) {
			for (KeyedResult<Integer, ValueData> kr: result) {
				LOGGER.debug("dataChange: "+kr.getKey()+" : "+kr.getValue());
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
	
	private static class W3CDateFormat extends SimpleDateFormat {
		private static final long serialVersionUID = 1L;

		public W3CDateFormat(String string) {
			super(string);
		}

		public Date parse(String source, ParsePosition pos) {    
	        return super.parse(source.replaceFirst(":(?=[0-9]{2}$)",""),pos);
	    }
	}
	
	private class SyncItemCallback implements DataCallback {

		public void changed(Item item, ItemState itemState) {
			LOGGER.debug("dataChange: "+item.getId()+" : "+itemState.getValue());
			Node itemNode = itemNodes.get(item.getId());
			JIVariant ji = itemState.getValue();
			short quality = itemState.getQuality();
			Calendar ts = itemState.getTimestamp();
			
			itemNode.setAttribute("quality", new Value(quality));
			String qualityString = Utils.qualityCodes.get(quality);
			if (qualityString == null) {
				qualityString = "";
			}
			itemNode.setAttribute("qualityString", new Value(qualityString));
			DateFormat dateFormat = new W3CDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
			itemNode.setAttribute("timestamp", new Value(dateFormat.format(ts.getTime())));
			
			if (ji == null ||  itemState.getErrorCode() != 0 || quality <= 28) {
				LOGGER.debug("Bad Read, setting value to null");
				itemNode.setValueType(ValueType.STRING);
				itemNode.setValue(new Value(""));
				return;
			}
			
			try {
				Entry<ValueType, Value> entry = getValueFromJI(ji);
				
				itemNode.setValueType(entry.getKey());
				itemNode.setValue(entry.getValue());
			} catch (JIException e) {
				LOGGER.debug("", e);
			}
			
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
		if (access != null) {
			try {
				access.addItem(itemId, callbackSync);
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
		if (access != null) access.removeItem(itemId);
		
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
	
	private void populateAccess() {
		if (access == null) return;
		for (Node n: subscribed.keySet()) {
			String itemId = n.getAttribute("item id").getString();
			try {
				access.addItem(itemId, callbackSync);
			} catch (JIException e) {
				LOGGER.debug("", e);
			} catch (AddFailedException e) {
				LOGGER.debug("", e);
			}
		}
	}
}
