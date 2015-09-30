package opc;

import java.net.MalformedURLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.namespace.QName;

import org.apache.commons.lang3.ArrayUtils;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.openscada.opc.xmlda.Connection;
import org.openscada.opc.xmlda.ItemRequest;
import org.openscada.opc.xmlda.Poller;
import org.openscada.opc.xmlda.SubscriptionListener;
import org.openscada.opc.xmlda.SubscriptionState;
import org.openscada.opc.xmlda.browse.BrowserListener;
import org.openscada.opc.xmlda.browse.BrowserState;
import org.openscada.opc.xmlda.requests.BrowseEntry;
import org.openscada.opc.xmlda.requests.ItemProperty;
import org.openscada.opc.xmlda.requests.ItemValue;
import org.openscada.opc.xmlda.requests.WriteRequest;
import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonArray;

public class XmlServer extends OpcServer {
	
	private Connection connection;
	private Poller poller;
	private Set<Node> subscribed = Collections.newSetFromMap(new ConcurrentHashMap<Node, Boolean>());
	
	private static final long SCAN_DELAY = 5500;
	private static final int BATCH_SIZE = 5;

	public XmlServer(OpcConn c, Node n) {
		super(c, n);
	}

	@Override
	protected void connect(String host, String domain, String user, String pass) {
		String url = node.getAttribute("url").getString();
		String service = node.getAttribute("service name").getString();
		
		try {
			connection = new Connection(url, service);
		} catch (MalformedURLException e) {
			LOGGER.error("", e);
        	stop();
		}
		poller = connection.createPoller(new ItemListener());
		populatePoller();
		stopped = false;

	}
	
	private void populatePoller() {
		if (poller == null) return;
		poller.setItems(subscribed.toArray(new String[subscribed.size()]));
		
	}

	@Override
	protected void onConnected() {
		connection.createRootBrowser(new TreeBuilder(node), SCAN_DELAY, BATCH_SIZE, true);
	}
	
	@Override
	protected void stop() {
		if (poller != null) {
			poller.setItems(new ArrayList<ItemRequest>());
			poller = null;
		}
		if (connection != null) {
			try {
				connection.close();
			} catch (Exception e) {
				LOGGER.debug("", e);
			}
			connection = null;
		}
		super.stop();
	}

	@Override
	protected Action getEditAction(String host, String domain, String user, String pass) {
		String url = node.getAttribute("url").getString();
		String serv = node.getAttribute("service name").getString();
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));
		act.addParameter(new Parameter("url", ValueType.STRING, new Value(url)));
		act.addParameter(new Parameter("service name", ValueType.STRING, new Value(serv)));
		return act;
	}
	
	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			String url = event.getParameter("url", ValueType.STRING).getString();
			String serv = event.getParameter("service name", ValueType.STRING).getString();
			if (name!=null && name.length()>0 && !name.equals(node.getName())) {
				Node newNode = node.getParent().createChild(name).build();
				newNode.setAttribute("url", new Value(url));
				newNode.setAttribute("service name", new Value(serv));
				XmlServer os = new XmlServer(conn, newNode);
				remove();
				os.restoreLastSession();
			} else {
			
				node.setAttribute("url", new Value(url));
				node.setAttribute("service name", new Value(serv));
			
				stop();
				init();
			}
		}
	}
	
	private class TreeBuilder implements BrowserListener {
		
		private Node parent;
		
		TreeBuilder(Node n) {
			this.parent = n;
		}
		
		public void dataChange(List<BrowseEntry> entries) {
			 for ( final BrowseEntry entry : entries ) {
				 Node child = null;
                 if (entry.isItem()) {
                	 child = parent.createChild(entry.getName()).setValueType(ValueType.STRING).build();
                	 child.setAttribute("item id", new Value(entry.getItemName()));
                	 child.setAttribute("item path", new Value(entry.getItemPath()));
                	 
                	 final List<ItemProperty> props = new ArrayList<ItemProperty> ( entry.getProperties ().values () );
                     for ( final ItemProperty prop : props ) {
                    	 Object propValObj = prop.getValue();
                    	 Value propVal = null;
                    	 if (propValObj instanceof String) propVal = new Value((String) propValObj);
                    	 else if (propValObj instanceof QName) propVal = new Value(((QName) propValObj).getLocalPart());
                    	 else if (propValObj!=null) propVal = valueFromItemValue(propValObj).getValue();
                    	 String propName = prop.getName().getLocalPart();
                    	 if (propVal!=null && propName!=null && propName.trim().length()>0) child.setAttribute(propName, propVal);
                    	 
                    	 if ("dataType".equals(propName)) {
                    		 if (propVal != null) {
                    			 String dt = propVal.getString().toLowerCase();
                    			 if (dt.startsWith("array")) child.setValueType(ValueType.ARRAY);
                    			 else if (dt.equals("boolean")) child.setValueType(ValueType.BOOL);
                    			 else if (dt.startsWith("unsigned") || dt.equals("int") || 
                    					 dt.equals("short") || dt.equals("long") || 
                    					 dt.equals("byte")|| dt.equals("double") || 
                    					 dt.equals("float")) child.setValueType(ValueType.NUMBER);
                    		 }
                    	 }
                     }
                     //System.out.println();
                	 
                	 setupNode(child);
                 }
                 if (entry.isParent()) {
                	 if (child == null) child = parent.createChild(entry.getName()).build();
                	 if (LAZY_LOAD) {
                		 child.getListener().setOnListHandler(new Handler<Node>() {
                			 private boolean loaded = false;
                			 public void handle(Node event) {
                				 if (!loaded) connection.createBrowser(entry, new TreeBuilder(event), SCAN_DELAY, BATCH_SIZE, true);
                				 loaded = true;
                			 }
                		 });
                	 } else {
                		 connection.createBrowser(entry, new TreeBuilder(child), SCAN_DELAY, BATCH_SIZE, true);
                	 }
                 }
			 }
			
		}

		public void stateChange(BrowserState arg0, Throwable arg1) {
			// TODO Auto-generated method stub
			
		}
	}
	
	private class ItemListener implements SubscriptionListener {

		public void stateChange(SubscriptionState state) {
			// TODO Auto-generated method stub
			
		}

		public void dataChange(Map<String, ItemValue> values) {
			for (Map.Entry<String, ItemValue> entry: values.entrySet()) {
				Node itemNode = itemNodes.get(entry.getKey());
				ItemValue ival = entry.getValue();
				Entry<ValueType, Value> valent = valueFromItemValue(ival != null ? ival.getValue(): null); 
				if (valent == null) {
					itemNode.setValue(null);
				} else {
					itemNode.setValueType(valent.getKey());
					itemNode.setValue(valent.getValue());
				}
				//LOGGER.info(entry.getKey() + " updated");
			}
			
		}
	}

	@Override
	public void addItemSub(Node event) {
		if (subscribed.contains(event)) return;
		String itemName = event.getAttribute("item id").getString();
		String itemPath = event.getAttribute("item path").getString();
		if (poller != null) {
			poller.addItem(new ItemRequest(itemName, itemName, itemPath));
		}
		
	}

	private Entry<ValueType, Value> valueFromItemValue(Object ival) {
		if (ival == null) return null;
		if (ival instanceof Boolean) {
			Value v = new Value((Boolean) ival);
			return new AbstractMap.SimpleImmutableEntry<ValueType, Value>(ValueType.BOOL, v);
		} else if (ival instanceof Number) {
			Value v = new Value((Number) ival);
			return new AbstractMap.SimpleImmutableEntry<ValueType, Value>(ValueType.NUMBER, v);
		} else if (ival instanceof Collection || isArray(ival)) {
			Collection<?> c = toCollection(ival);
			JsonArray jsonArr = new JsonArray();
			for (Object o: c) {
				Entry<ValueType, Value> valent = valueFromItemValue(o);
				if (valent != null) {
					ValueType vtype = valent.getKey();
					Value val = valent.getValue();
					if (ValueType.ARRAY.compare(vtype)) jsonArr.addArray(val.getArray());
					else if (ValueType.BOOL.compare(vtype)) jsonArr.addBoolean(val.getBool());
					else if (ValueType.NUMBER.compare(vtype)) jsonArr.addNumber(val.getNumber());
					else jsonArr.addString(val.getString());
				} else {
					jsonArr.add(null);
				}
			}
			Value v = new Value(jsonArr);
			return new AbstractMap.SimpleImmutableEntry<ValueType, Value>(ValueType.ARRAY, v);
		} else if (ival instanceof GregorianCalendar) {
			Value v = new Value(((GregorianCalendar) ival).getTime().toString());
			return new AbstractMap.SimpleImmutableEntry<ValueType, Value>(ValueType.STRING, v);
		} else {
			Value v = new Value(ival.toString());
			return new AbstractMap.SimpleImmutableEntry<ValueType, Value>(ValueType.STRING, v);
		}
	}

	private Collection<?> toCollection(Object ival) {
		if (ival instanceof Collection) return (Collection<?>) ival;
		if (ival instanceof Object[]) return Arrays.asList((Object[]) ival);
		if (ival instanceof boolean[]) return Arrays.asList(ArrayUtils.toObject((boolean[]) ival));
		if (ival instanceof byte[]) return Arrays.asList(ArrayUtils.toObject((byte[]) ival));
		if (ival instanceof short[]) return Arrays.asList(ArrayUtils.toObject((short[]) ival));
		if (ival instanceof char[]) return Arrays.asList(ArrayUtils.toObject((char[]) ival));
		if (ival instanceof int[]) return Arrays.asList(ArrayUtils.toObject((int[]) ival));
		if (ival instanceof long[]) return Arrays.asList(ArrayUtils.toObject((long[]) ival));
		if (ival instanceof float[]) return Arrays.asList(ArrayUtils.toObject((float[]) ival));
		if (ival instanceof double[]) return Arrays.asList(ArrayUtils.toObject((double[]) ival));
		return new ArrayList<Object>();
	}

	private boolean isArray(Object ival) {
		return (ival instanceof Object[] || ival instanceof boolean[] || ival instanceof byte[] || 
				ival instanceof short[] || ival instanceof char[] || ival instanceof int[] || 
				ival instanceof long[] || ival instanceof float[] || ival instanceof double[]);
	}

	@Override
	public void removeItemSub(Node event) {
		subscribed.remove(event);
		String itemName = event.getAttribute("item id").getString();
		String itemPath = event.getAttribute("item path").getString();
		if (poller != null) {
			poller.removeItem(new ItemRequest(itemName, itemName, itemPath));
		}
		
	}

	@Override
	protected Handler<ValuePair> getSetHandler(Node child) {
		return new SetHandler(child);
	}
	
	private static enum ArrayType {BOOL, NUM, DATE, BYTE, STR};
	
	private class SetHandler implements Handler<ValuePair> {
		private Node itemNode;
		SetHandler(Node n) {
			itemNode = n;
		}
		public void handle(ValuePair event) {
			if (!event.isFromExternalSource()) return;
			Value newVal = event.getCurrent();
			ValueType vtype = newVal.getType();
			String itemName = itemNode.getAttribute("item id").getString();
			String itemPath = itemNode.getAttribute("item path").getString();
			Object val;
			if (ValueType.ARRAY.compare(vtype)) {
				if (itemNode.getAttribute("dataType") != null) {
					String dt = itemNode.getAttribute("dataType").getString().toLowerCase();
					ArrayType atype;
					if (dt.contains("boolean")) atype = ArrayType.BOOL;
					else if (dt.contains("unsigned") || dt.contains("int") || 
       					 dt.contains("short") || dt.contains("long") || 
       					 dt.contains("byte")|| dt.contains("double") || 
       					 dt.contains("float")) atype = ArrayType.NUM;
					else if (dt.contains("datetime")) atype = ArrayType.DATE;
					else if (dt.contains("binary")) atype = ArrayType.BYTE;
					else atype = ArrayType.STR;
					val = toTypedList(newVal.getArray(), atype);
					if (val == null) return;
				} else val = newVal.getArray().toList();
			} else if (ValueType.BOOL.compare(vtype)) val = newVal.getBool();
			else if (ValueType.NUMBER.compare(vtype)) val = newVal.getNumber();
			else if (itemNode.getAttribute("dataType") != null && itemNode.getAttribute("dataType").getString().equals("dateTime")) {
				try {
					SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", new Locale("us"));
					Date date = sdf.parse(newVal.getString());
					GregorianCalendar c = new GregorianCalendar();
					c.setTime(date);
					val = c;
				} catch (ParseException e) {
					LOGGER.debug("", e);
					return;
				}
			} else {
				val = newVal.getString();
			}
			
			connection.scheduleTask(new WriteRequest(new ItemValue(itemName, itemPath, val, null, new GregorianCalendar(), null)));
			
		}
	}

	public Object toTypedList(JsonArray jarr, ArrayType atype) {
		switch (atype) {
		case BOOL: {
			ArrayList<Boolean> list = new ArrayList<Boolean>();
			for (Object o: jarr) {
				if (o instanceof Boolean) list.add((Boolean) o);
				else list.add(Boolean.parseBoolean(o.toString()));
			}
			return list;
		}
		case NUM: {
			ArrayList<Number> list = new ArrayList<Number>();
			for (Object o: jarr) {
				if (o instanceof Number) list.add((Number) o);
				else list.add(Double.parseDouble(o.toString()));
			}
			return list;
		}
		case DATE: {
			ArrayList<GregorianCalendar> list = new ArrayList<GregorianCalendar>();
			for (Object o: jarr) {
				try {
					SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", new Locale("us"));
					Date date  = sdf.parse(o.toString());
					GregorianCalendar c = new GregorianCalendar();
					c.setTime(date);
					list.add(c);
				} catch (ParseException e) {
					LOGGER.debug("", e);
					return null;
				}
			}
			return list;
		}
		case BYTE: {
			byte[] list = new byte[jarr.size()];
			for (int i=0; i<jarr.size(); i++) {
				list[i] = Byte.parseByte(jarr.get(i).toString());
			}
			return list;
		}
		case STR: {
			ArrayList<String> list = new ArrayList<String>();
			for (Object o: jarr) {
				list.add(o.toString());
			}
			return list;
		}
		}
		return null;
	}
	
	
	
}
