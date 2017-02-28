package opc;

import org.dsa.iot.dslink.DSLink;
import org.dsa.iot.dslink.DSLinkFactory;
import org.dsa.iot.dslink.DSLinkHandler;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.NodeManager;
import org.dsa.iot.dslink.serializer.Deserializer;
import org.dsa.iot.dslink.serializer.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main extends DSLinkHandler {
	private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
	
	private DSLink link;
	
	public static void main(String[] args) throws Exception {
		//args = new String[] { "-b", "http://localhost:8080/conn", "-l", "debug" };
		DSLinkFactory.start(args, new Main());
	}
	
	@Override
	public boolean isResponder() {
		return true;
	}
	
	@Override
	public void onResponderConnected(DSLink link){
		LOGGER.info("Connected");
		
		this.link = link;
		NodeManager manager = link.getNodeManager();
		Serializer copyser = new Serializer(manager);
		Deserializer copydeser = new Deserializer(manager);
        Node superRoot = manager.getNode("/").getNode();
        OpcLink.start(superRoot, copyser, copydeser);
	}
	
	@Override
	public Node onSubscriptionFail(String path) {
		NodeManager manager = link.getNodeManager();
        String[] split = NodeManager.splitPath(path);
        Node superRoot = manager.getSuperRoot();
        try {
			Thread.sleep(3000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
		}
        Node n = superRoot;
        int i = 0;
        while (i < split.length) {        	
        	n = n.getChild(split[i], false);
        	n.getListener().postListUpdate();
        	i++;
        }
        return n;
	}
	@Override
	public Node onInvocationFail(final String path) {
		final String[] split = NodeManager.splitPath(path);
		NodeManager manager = link.getNodeManager();
		Node superRoot = manager.getSuperRoot();
		try {
			Thread.sleep(3000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
		}
        Node n = superRoot;
        int i = 0;
        while (i < split.length) {        	
        	n = n.getChild(split[i], false);
        	n.getListener().postListUpdate();
        	i++;
        }
        return n;
	}

}
