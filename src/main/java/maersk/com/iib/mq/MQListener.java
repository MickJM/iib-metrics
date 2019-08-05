package maersk.com.iib.mq;

/*
 * JMS MQListener object to 'do all the work'
 *  
 */
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.jms.BytesMessage;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;

public class MQListener implements MessageListener{

	/*
	 * mqsichangeresourcestats TSTIPD01 -c active
	 * 
	 * IIB.METRICS
	 * 
	 * https://www.ibm.com/support/knowledgecenter/en/SSMKHH_9.0.0/com.ibm.etools.mft.doc/bj43370_.htm
	 *
	 */
	private Logger log = Logger.getLogger(this.getClass());
	
	private static final String METRICPREFIX = "iib_metrics:";

	
	//JVM Summary
    private Map<String,Map<String,AtomicInteger>>collectionSummaryMaps 
			= new HashMap<String,Map<String,AtomicInteger>>();

    /*
    private Map<String,AtomicInteger>sinitMem = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>susedMem = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>scommitedMem = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>smaxMem = new HashMap<String,AtomicInteger>();
	*/
    
	//JVM Heap Memory
    private Map<String,Map<String,AtomicInteger>>collectionHeapMessageMaps 
			= new HashMap<String,Map<String,AtomicInteger>>();
    /*
    private Map<String,AtomicInteger>hinitMem = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>husedMem = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>hcommitedMem = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>hmaxMem = new HashMap<String,AtomicInteger>();
    */
    
	//JVM None Heap Memory
    private Map<String,Map<String,AtomicInteger>>collectionNoneHeapMessageMaps 
    		= new HashMap<String,Map<String,AtomicInteger>>();
    /*
    private Map<String,AtomicInteger>ninitMem = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>nusedMem = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>ncommitedMem = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>nmaxMem = new HashMap<String,AtomicInteger>();
    */
    
	//JVM Garbage Collection
    private Map<String,Map<String,AtomicInteger>>collectionscavengeMaps 
			= new HashMap<String,Map<String,AtomicInteger>>();
    private Map<String,Map<String,AtomicInteger>>collectionglobMaps 
			= new HashMap<String,Map<String,AtomicInteger>>();

    /*
    private Map<String,AtomicInteger>scavCUMGCT = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>scavCUMNo = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>globCUMGCT = new HashMap<String,AtomicInteger>();
    private Map<String,AtomicInteger>globCUMNo = new HashMap<String,AtomicInteger>();
	*/

	//JVM Summary
    private Map<String,Map<String,AtomicInteger>>ODBCSummaryMaps 
			= new HashMap<String,Map<String,AtomicInteger>>();


    
    
    // IIB Status
    private Map<String,AtomicInteger>iibStatusMap = new HashMap<String, AtomicInteger>();

    private Session session = null;
	private DocumentBuilderFactory docBuilder = null;
	
	private boolean updatingMetrics = false;
	
	@Value("${application.debug:false}")
	private boolean _debug;
	
	private String brokerName;
	public String getBrokerName() {
		return brokerName;
	}
	public void setBrokerName(String value) {
		this.brokerName = value;
	}

	private String executionGroup;
	public String getExecutionGroup() {
		return executionGroup;
	}
	public void setExecutionGroup(String value) {
		this.executionGroup = value;
	}
	
	public MQListener(Session session, String brokerName) {
		this.session = session;
		this.docBuilder = DocumentBuilderFactory.newInstance();
		setBrokerName(brokerName);

	}
	
	/*
	 * Called when a message is read from the queue
	 */
	//@Bean
	@Override
	public void onMessage(Message message) {

		setUpdating(true);
		
		/*
		 * Get the message and create an XML object 
		 * ... IIB metrics are generated in XML
		 */
		Document xmlDoc = null;
		String text = null;
		try {
			if (message instanceof TextMessage)
		    {
		        text = ((TextMessage) message).getText();
		    } else {
	            byte[] body = new byte[(int) ((BytesMessage) message).getBodyLength()];
	            ((BytesMessage) message).readBytes(body);
	            text = new String(body);
	        }

			DocumentBuilder builder = this.docBuilder.newDocumentBuilder();
			ByteArrayInputStream insrc = new ByteArrayInputStream(text.getBytes());
			xmlDoc = builder.parse(insrc);
			
		} catch (ParserConfigurationException | IOException | SAXException | JMSException e1) {
			log.error(e1.getMessage());
			
			try {
				this.session.commit();
			} catch (JMSException e) {
				log.error("(A) Unable to commit message - " + e.getMessage());
			}
			
		}

		// Set the broker status to say its 'Running'
		try {
			SetBrokerStatus(1);	
			
		} catch (Exception e) {
			//do nothing
		}
		
		// Point to the ResourceStatistics and process the nodes
		NodeList resList = xmlDoc.getElementsByTagName("ResourceStatistics");
		for (int i = 0; i < resList.getLength(); i++) {
			Node r = resList.item(i);
			if (r.getNodeType() == Node.ELEMENT_NODE) {
				Element resStat = (Element) r;
				setBrokerName(resStat.getAttribute("brokerLabel"));
				setExecutionGroup(resStat.getAttribute("executionGroupName"));
				NodeList resTypes = resStat.getChildNodes();
				ProcessResourceTypes(resTypes);
			}
		}
			
		// All good, commit the message from the queue
		try {
			this.session.commit();
		} catch (JMSException e) {
			log.error("(B) Unable to commit message - " + e.getMessage());
		}

	}

	// if we are updating metrics, say we are ...
	public synchronized void setUpdating(boolean b) {
		this.updatingMetrics = b;
	}
	
	public synchronized boolean getUpdating() {
		return this.updatingMetrics; 
		
	}
	
	// Reset metrics	
	public void ResetMetrics() {

		SetBrokerStatus(0);	
		
		try {
			SetMetrics();
			
		} catch (Exception e) {
			// do nothing
		}
	}

	/*
	 * Loop through each map ...
	 */
	private void SetMetrics() throws Exception {
		
		try {
			Iterator<?> summary = this.collectionSummaryMaps.entrySet().iterator();
			Iterator<?> heap = this.collectionHeapMessageMaps.entrySet().iterator();
			Iterator<?> none = this.collectionNoneHeapMessageMaps.entrySet().iterator();
			Iterator<?> scav = this.collectionscavengeMaps.entrySet().iterator();
			Iterator<?> glob = this.collectionglobMaps.entrySet().iterator();

			while (summary.hasNext()) {
		        Map.Entry pair = (Map.Entry)summary.next();
		        String executionGroup = (String) pair.getKey();
		        Map<String,AtomicLong> m = (Map<String, AtomicLong>) pair.getValue();
		        SetMetrics(pair,"summary");
		    }			

			while (heap.hasNext()) {
		        Map.Entry pair = (Map.Entry)heap.next();
		        String executionGroup = (String) pair.getKey();
		        Map<String,AtomicLong> m = (Map<String, AtomicLong>) pair.getValue();
		        SetMetrics(pair,"Heap Memory");
		    }			

			while (none.hasNext()) {
		        Map.Entry pair = (Map.Entry)none.next();
		        String executionGroup = (String) pair.getKey();
		        Map<String,AtomicLong> m = (Map<String, AtomicLong>) pair.getValue();
		        SetMetrics(pair,"None-Heap Memory");
		    }			
			
			while (scav.hasNext()) {
		        Map.Entry pair = (Map.Entry)none.next();
		        String executionGroup = (String) pair.getKey();
		        Map<String, AtomicLong> m = (Map<String, AtomicLong>) pair.getValue();
		        setJVMGarbageCollectionScav(pair,"Garbage Collection - scavenge");
		    }			
			
			while (glob.hasNext()) {
		        Map.Entry pair = (Map.Entry)none.next();
		        String executionGroup = (String) pair.getKey();
		        Map<String, AtomicLong> m = (Map<String, AtomicLong>) pair.getValue();
		        setJVMGarbageCollectionGlob(pair,"Garbage Collection - global");
		    }			
			
		} catch (Exception e) {
			// do nothing
		}
		
		
		
		//
		// InitialMemoryInMB
		/*
		AtomicInteger imem = sinitMem.get("InitialMemoryInMB");
		setInitialMemoryInMB((0),
				sinitMem, imem, "summary");
		
		// UsedMemoryInMB 
		AtomicInteger umem = susedMem.get("UsedMemoryInMB");
		setUsedMemoryInMB((0),
				susedMem, umem, "summary");
		
		// CommittedMemoryInMB
		AtomicInteger cmem = scommitedMem.get("CommittedMemoryInMB");
		setMaxMemoryInMB((0),
				scommitedMem, cmem, "summary");
		
		// Max memory
		AtomicInteger maxmem = smaxMem.get("MaxMemoryInMB");
		setCommittedMemoryInMB((0),
				smaxMem, maxmem, "summary");
		*/
		
		//
		// InitialMemoryInMB
		/*
		imem = hinitMem.get("InitialMemoryInMB");
		setInitialMemoryInMB((0),
				hinitMem, imem, "Heap Memory");

		// UsedMemoryInMB 
		umem = husedMem.get("UsedMemoryInMB");
		setUsedMemoryInMB((0),
				husedMem, umem, "Heap Memory");
		
		// CommittedMemoryInMB
		cmem = hcommitedMem.get("CommittedMemoryInMB");
		setMaxMemoryInMB((0),
				hcommitedMem, cmem, "Heap Memory");

		// Max memory
		maxmem = hmaxMem.get("MaxMemoryInMB");
		setCommittedMemoryInMB((0),
				hmaxMem, maxmem, "Heap Memory");
		*/

		//
		// InitialMemoryInMB
		/*
		imem = ninitMem.get("InitialMemoryInMB");
		setInitialMemoryInMB((0),
				ninitMem, imem, "None-Heap Memory");

		// UsedMemoryInMB 
		umem = nusedMem.get("UsedMemoryInMB");
		setUsedMemoryInMB((0),
				nusedMem, umem, "None-Heap Memory");
		
		// CommittedMemoryInMB
		cmem = ncommitedMem.get("CommittedMemoryInMB");
		setMaxMemoryInMB((0),
				ncommitedMem, cmem, "None-Heap Memory");

		// Max memory
		maxmem = nmaxMem.get("MaxMemoryInMB");
		setCommittedMemoryInMB((0),
				nmaxMem, maxmem, "None-Heap Memory");
		
		*/
		
	}
	
	private void SetMetrics(Map.Entry<?, ?> m, String type) throws Exception {
		//
        String executionGroup = (String) m.getKey();
        Map<String,AtomicInteger> map = (Map<String, AtomicInteger>) m.getValue();

		// InitialMemoryInMB
		AtomicInteger imem = map.get(new StringBuilder()
				.append(METRICPREFIX)
				.append("InitialMemoryInMB")
				.toString());
		setInitialMemoryInMB((0),
				map, imem, type);
		
		// UsedMemoryInMB 
		AtomicInteger umem = map.get(new StringBuilder()
				.append(METRICPREFIX)
				.append("UsedMemoryInMB")
				.toString());
		setUsedMemoryInMB((0),
				map, umem, type);
		
		// CommittedMemoryInMB
		AtomicInteger cmem = map.get(new StringBuilder()
				.append(METRICPREFIX)
				.append("CommittedMemoryInMB")
				.toString());
		setCommittedMemoryInMB((0),
				map, cmem, type);
		
		// MaxMemoryInMB
		AtomicInteger maxmem = map.get(new StringBuilder()
				.append(METRICPREFIX)
				.append("MaxMemoryInMB")
				.toString());
		setMaxMemoryInMB((0),
				map, maxmem, type);
		
	}

	
	private void setJVMGarbageCollectionScav(Map.Entry<?, ?> m, String type) throws Exception {
		
        String executionGroup = (String) m.getKey();
        Map<String,AtomicInteger> map = (Map<String, AtomicInteger>) m.getValue();

		AtomicInteger s = map.get("scav:CumulativeGCTimeInSeconds");
		setCumulativeGCTimeInSeconds("scav:CumulativeGCTimeInSeconds", (0), map, s, type);

		AtomicInteger n = map.get("scav:CumulativeNumberOfGCCollections");
		setCumulativeGCTimeInSeconds("scav:CumulativeGCTimeInSeconds", (0), map, n, type);

	}
	
	private void setJVMGarbageCollectionGlob(Map.Entry<?, ?> m, String type) throws Exception {
		
        String executionGroup = (String) m.getKey();
        Map<String,AtomicInteger> map = (Map<String, AtomicInteger>) m.getValue();

		AtomicInteger s = map.get("glob:CumulativeGCTimeInSeconds");
		setCumulativeGCTimeInSeconds("glob:CumulativeGCTimeInSeconds", (0), map, s, type);

		AtomicInteger n = map.get("glob:CumulativeNumberOfGCCollections");
		setCumulativeGCTimeInSeconds("glob:CumulativeGCTimeInSeconds", (0), map, n, type);

	}

	
	private void SetBrokerStatus(int value) {

		// Got something from IIB, so set to all okay ...
		try {
			AtomicInteger iib = iibStatusMap.get("iibStatus");
			if (iib == null) {
				iibStatusMap.put("iibStatus", Metrics.gauge(new StringBuilder()
						.append(METRICPREFIX)
						.append("iibStatus")
						.toString(), 
						Tags.of("iibName", getBrokerName()), 
						new AtomicInteger(value)));
			} else {
				iib.set(value);
			}        
		} catch (Exception e) {
			// dont do anything ...
		}
	}
	
	
	/*
	 * Process the message
	 * Currently, only processing JVM elements
	 */
	private void ProcessResourceTypes(NodeList resTypes) {

		for (int i = 0; i < resTypes.getLength(); i++) {
			Node r = resTypes.item(i);
			if (r.getNodeType() == Node.ELEMENT_NODE) {
				Element resTy = (Element) r;
				String name = resTy.getAttribute("name");
				if (name.equals("JVM") || (name.equals("Parsers")))
					if (name.equals("JVM")) {
						NodeList jvmList = r.getChildNodes();
						ProcessJVM(jvmList);
					}
					if (name.equals("ODBC")) {
						NodeList jvmList = r.getChildNodes();
						ProcessODBC(jvmList);
						
					}
			}
			
		}
		
	}

	/*
	 * Process JVM messages
	 * 
	 *  <ResourceType name="JVM">
  <resourceIdentifier name="summary" InitialMemoryInMB="289" UsedMemoryInMB="50" CommittedMemoryInMB="323" MaxMemoryInMB="-1" CumulativeGCTimeInSeconds="1" CumulativeNumberOfGCCollections="24"/>
  <resourceIdentifier name="Heap Memory" InitialMemoryInMB="32" UsedMemoryInMB="24" CommittedMemoryInMB="39" MaxMemoryInMB="256"/>
  <resourceIdentifier name="Non-Heap Memory" InitialMemoryInMB="257" UsedMemoryInMB="26" CommittedMemoryInMB="284" MaxMemoryInMB="-1"/>
  <resourceIdentifier name="Garbage Collection - scavenge" CumulativeGCTimeInSeconds="1" CumulativeNumberOfGCCollections="24"/>
  <resourceIdentifier name="Garbage Collection - global" CumulativeGCTimeInSeconds="0" CumulativeNumberOfGCCollections="0"/>
 		</ResourceType>

	 */
	private void ProcessJVM(NodeList jvmList) {
		
		for (int i = 0; i < jvmList.getLength(); i++) {
			Node j = jvmList.item(i);
			if (j.getNodeType() == Node.ELEMENT_NODE) {
				Element jvm = (Element) j;
				String name = jvm.getAttribute("name");
				switch (name.trim()) {
					case "summary": {
						JVMSummary(jvm);
						break;
					}
					case "Heap Memory": {
						JVMHeapMemory(jvm);
						break;
					}
					case "Non-Heap Memory": {
						JVMNoneHeapMemory(jvm);
						break;
					}
					case "Garbage Collection - scavenge": {
						JVMGarbageCollectionScav(jvm);
						break;
					}
					case "Garbage Collection - global": {
						JVMGarbageCollectionGlob(jvm);
						break;
					}
					
					default:{
						
					}
				}	
			}
		}	
	}

	/*
	 * JMV data
	 */
	private void JVMSummary(Element jvm) {

		if (this._debug) {log.info("JVM Summary"); }
		Element sum = (Element) jvm;

		Map<String, AtomicInteger>flowMap 
				= collectionSummaryMaps.get(getExecutionGroup());
		if (flowMap == null) {
			Map<String, AtomicInteger>stats 
					= new HashMap<String,AtomicInteger>();
			collectionSummaryMaps.put(getExecutionGroup(), stats);
		}

		// InitialMemoryInMB
		Map<String,AtomicInteger>sinitMem = collectionSummaryMaps.get(getExecutionGroup());
		AtomicInteger imem = sinitMem.get(new StringBuilder()
				.append(METRICPREFIX)
				.append("InitialMemoryInMB")
				.toString());
		setInitialMemoryInMB(Integer.parseInt(sum.getAttribute("InitialMemoryInMB")),
				sinitMem, imem, "summary");
		
		// UsedMemoryInMB 
		Map<String,AtomicInteger>susedMem = collectionSummaryMaps.get(getExecutionGroup());
		AtomicInteger umem = susedMem.get(new StringBuilder()
				.append(METRICPREFIX)
				.append("UsedMemoryInMB")
				.toString());
		setUsedMemoryInMB(Integer.parseInt(sum.getAttribute("UsedMemoryInMB")),
				susedMem, umem, "summary");
		
		// CommittedMemoryInMB
		Map<String,AtomicInteger>scommitedMem = collectionSummaryMaps.get(getExecutionGroup());		
		AtomicInteger cmem = scommitedMem.get(new StringBuilder()
				.append(METRICPREFIX)
				.append("CommittedMemoryInMB")
				.toString());
		setCommittedMemoryInMB(Integer.parseInt(sum.getAttribute("CommittedMemoryInMB")),
				scommitedMem, cmem, "summary");
		
		// Max memory
		Map<String,AtomicInteger>smaxMem = collectionSummaryMaps.get(getExecutionGroup());				
		AtomicInteger maxmem = smaxMem.get(new StringBuilder()
				.append(METRICPREFIX)
				.append("MaxMemoryInMB")
				.toString());
		setMaxMemoryInMB(Integer.parseInt(sum.getAttribute("MaxMemoryInMB")),
				smaxMem, maxmem, "summary");
		
	}
	
	/*
	 * JVM Heap memory
	 */
	private void JVMHeapMemory(Element jvm) {

		if (this._debug) {log.info("JVM HeapMemory"); }
		Element heap = (Element) jvm;

		Map<String, AtomicInteger>flowMap 
				= collectionHeapMessageMaps.get(getExecutionGroup());
		if (flowMap == null) {
			Map<String, AtomicInteger>stats 
					= new HashMap<String,AtomicInteger>();
			collectionHeapMessageMaps.put(getExecutionGroup(), stats);
		}
		
		// InitialMemoryInMB
		Map<String,AtomicInteger>hinitMem = collectionHeapMessageMaps.get(getExecutionGroup());
		AtomicInteger imem = hinitMem.get(new StringBuilder()
				.append(METRICPREFIX)
				.append("InitialMemoryInMB")
				.toString());
		setInitialMemoryInMB(Integer.parseInt(heap.getAttribute("InitialMemoryInMB")),
				hinitMem, imem, "Heap Memory");

		// UsedMemoryInMB 
		Map<String,AtomicInteger>husedMem = collectionHeapMessageMaps.get(getExecutionGroup());
		AtomicInteger umem = husedMem.get(new StringBuilder()
				.append(METRICPREFIX)
				.append("UsedMemoryInMB")
				.toString());
		setUsedMemoryInMB(Integer.parseInt(heap.getAttribute("UsedMemoryInMB")),
				husedMem, umem, "Heap Memory");
		
		// CommittedMemoryInMB
		Map<String,AtomicInteger>hcommitedMem = collectionHeapMessageMaps.get(getExecutionGroup());
		AtomicInteger cmem = hcommitedMem.get(new StringBuilder()
				.append(METRICPREFIX)
				.append("CommittedMemoryInMB")
				.toString());
		setCommittedMemoryInMB(Integer.parseInt(heap.getAttribute("CommittedMemoryInMB")),
				hcommitedMem, cmem, "Heap Memory");
		
		// Max memory
		Map<String,AtomicInteger>hmaxMem = collectionHeapMessageMaps.get(getExecutionGroup());		
		AtomicInteger maxmem = hmaxMem.get(new StringBuilder()
				.append(METRICPREFIX)
				.append("MaxMemoryInMB")
				.toString());
		setMaxMemoryInMB(Integer.parseInt(heap.getAttribute("MaxMemoryInMB")),
				hmaxMem, maxmem, "Heap Memory");
		

		
		/*
		if (imem == null) {
			hinitMem.put("InitialMemoryInMB", Metrics.gauge("InitialMemoryInMB", 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","JVM",
							"type","Heap Memory"),
					new AtomicInteger(
							Integer.parseInt(heap.getAttribute("InitialMemoryInMB"))))
					);
		} else {
			imem.set(Integer.parseInt(heap.getAttribute("InitialMemoryInMB")));
		}
		
		
		// UsedMemoryInMB 
		AtomicInteger umem = husedMem.get("UsedMemoryInMB");
		if (umem == null) {
			husedMem.put("UsedMemoryInMB", Metrics.gauge("UsedMemoryInMB", 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","JVM",
							"type","Heap Memory"),
					new AtomicInteger(
							Integer.parseInt(heap.getAttribute("UsedMemoryInMB"))))
					);
		} else {
			umem.set(Integer.parseInt(heap.getAttribute("UsedMemoryInMB")));
		}

		// CommittedMemoryInMB 
		AtomicInteger cmem = hcommitedMem.get("CommittedMemoryInMB");
		if (cmem == null) {
			hcommitedMem.put("CommittedMemoryInMB", Metrics.gauge("CommittedMemoryInMB", 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","JVM",
							"type","Heap Memory"),
					new AtomicInteger(
							Integer.parseInt(heap.getAttribute("CommittedMemoryInMB"))))
					);
		} else {
			cmem.set(Integer.parseInt(heap.getAttribute("CommittedMemoryInMB")));
		}

		// Max memory
		AtomicInteger maxmem = hmaxMem.get("MaxMemoryInMB");
		if (maxmem == null) {
			hmaxMem.put("MaxMemoryInMB", Metrics.gauge("MaxMemoryInMB", 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","JVM",
							"type","Heap Memory"),
					new AtomicInteger(
							Integer.parseInt(heap.getAttribute("MaxMemoryInMB"))))
					);
		} else {
			maxmem.set(Integer.parseInt(heap.getAttribute("MaxMemoryInMB")));
		}
		*/
		
	}

	/*
	 * JVM NoneHeap Memory
	 */
	private void JVMNoneHeapMemory(Element jvm) {

		if (this._debug) {log.info("JVM None Heap Memory"); }
		Element nheap = (Element) jvm;

		Map<String, AtomicInteger>flowMap 
					= collectionNoneHeapMessageMaps.get(getExecutionGroup());
		if (flowMap == null) {
		    Map<String, AtomicInteger>stats 
		    			= new HashMap<String,AtomicInteger>();
		    collectionNoneHeapMessageMaps.put(getExecutionGroup(), stats);
		}

	    // InitialMemoryInMB
	    Map<String,AtomicInteger>ninitMem = collectionNoneHeapMessageMaps.get(getExecutionGroup());
		AtomicInteger imem = ninitMem.get(new StringBuilder()
				.append(METRICPREFIX)
				.append("InitialMemoryInMB")
				.toString());
		setInitialMemoryInMB(Integer.parseInt(nheap.getAttribute("InitialMemoryInMB")),
				ninitMem, imem, "None-Heap Memory");

		// UsedMemoryInMB 
	    Map<String,AtomicInteger>nusedMem = collectionNoneHeapMessageMaps.get(getExecutionGroup());
		AtomicInteger umem = nusedMem.get(new StringBuilder()
				.append(METRICPREFIX)
				.append("UsedMemoryInMB")
				.toString());
		setUsedMemoryInMB(Integer.parseInt(nheap.getAttribute("UsedMemoryInMB")),
				nusedMem, umem, "None-Heap Memory");
		
		// CommittedMemoryInMB
	    Map<String,AtomicInteger>ncommitedMem = collectionNoneHeapMessageMaps.get(getExecutionGroup());
		AtomicInteger cmem = ncommitedMem.get(new StringBuilder()
				.append(METRICPREFIX)
				.append("CommittedMemoryInMB")
				.toString());
		setCommittedMemoryInMB(Integer.parseInt(nheap.getAttribute("CommittedMemoryInMB")),
				ncommitedMem, cmem, "None-Heap Memory");

		// Max memory
	    Map<String,AtomicInteger>nmaxMem = collectionNoneHeapMessageMaps.get(getExecutionGroup());		
		AtomicInteger maxmem = nmaxMem.get(new StringBuilder()
				.append(METRICPREFIX)
				.append("MaxMemoryInMB")
				.toString());
		setMaxMemoryInMB(Integer.parseInt(nheap.getAttribute("MaxMemoryInMB")),
				nmaxMem, maxmem, "None-Heap Memory");
		


		/*
		// InitialMemoryInMB
		AtomicInteger imem = ninitMem.get("InitialMemoryInMB");
		if (imem == null) {
			ninitMem.put("InitialMemoryInMB", Metrics.gauge("InitialMemoryInMB", 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","JVM",
							"type","None-Heap Memory"),
					new AtomicInteger(
							Integer.parseInt(nheap.getAttribute("InitialMemoryInMB"))))
					);
		} else {
			imem.set(Integer.parseInt(nheap.getAttribute("InitialMemoryInMB")));
		}

		// UsedMemoryInMB 
		AtomicInteger umem = nusedMem.get("UsedMemoryInMB");
		if (umem == null) {
			nusedMem.put("UsedMemoryInMB", Metrics.gauge("UsedMemoryInMB", 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","JVM",
							"type","None-Heap Memory"),
					new AtomicInteger(
							Integer.parseInt(nheap.getAttribute("UsedMemoryInMB"))))
					);
		} else {
			umem.set(Integer.parseInt(nheap.getAttribute("UsedMemoryInMB")));
		}

		// CommittedMemoryInMB 
		AtomicInteger cmem = ncommitedMem.get("CommittedMemoryInMB");
		if (cmem == null) {
			ncommitedMem.put("CommittedMemoryInMB", Metrics.gauge("CommittedMemoryInMB", 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","JVM",
							"type","None-Heap Memory"),
					new AtomicInteger(
							Integer.parseInt(nheap.getAttribute("CommittedMemoryInMB"))))
					);
		} else {
			cmem.set(Integer.parseInt(nheap.getAttribute("CommittedMemoryInMB")));
		}

		// Max memory
		AtomicInteger maxmem = nmaxMem.get("MaxMemoryInMB");
		if (maxmem == null) {
			nmaxMem.put("MaxMemoryInMB", Metrics.gauge("MaxMemoryInMB", 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","JVM",
							"type","None-Heap Memory"),
					new AtomicInteger(
							Integer.parseInt(nheap.getAttribute("MaxMemoryInMB"))))
					);
		} else {
			maxmem.set(Integer.parseInt(nheap.getAttribute("MaxMemoryInMB")));
		}
		*/
		
	}

	/*
	 * JVM Garbage Collection - Scavenge
	 */
	private void JVMGarbageCollectionScav(Element jvm) {

		if (this._debug) {log.info("JVM Garbage Collection SCAV"); }
		Element gar = (Element) jvm;

		Map<String, AtomicInteger>flowMap 
				= collectionscavengeMaps.get(getExecutionGroup());
		if (flowMap == null) {
			Map<String, AtomicInteger>stats 
					= new HashMap<String,AtomicInteger>();
			collectionscavengeMaps.put(getExecutionGroup(), stats);
		}

		int timeInSeconds = Integer.parseInt(gar.getAttribute("CumulativeGCTimeInSeconds"));
		int no = Integer.parseInt(gar.getAttribute("CumulativeNumberOfGCCollections"));

		// Garbage collection time in seconds
	    Map<String,AtomicInteger>scavCUMGCT = collectionscavengeMaps.get(getExecutionGroup());
		AtomicInteger s = scavCUMGCT.get("scav:CumulativeGCTimeInSeconds");
		setCumulativeGCTimeInSeconds("scav:CumulativeGCTimeInSeconds",
				Integer.parseInt(gar.getAttribute("CumulativeGCTimeInSeconds")),
				scavCUMGCT, s, "Garbage Collection - scavenge");

	    Map<String,AtomicInteger>scavCUMNo = collectionscavengeMaps.get(getExecutionGroup());
		AtomicInteger n = scavCUMNo.get("scav:CumulativeNumberOfGCCollections");
		setCumulativeGCTimeInSeconds("scav:CumulativeNumberOfGCCollections", 
				Integer.parseInt(gar.getAttribute("CumulativeNumberOfGCCollections")),
				scavCUMNo, n, "Garbage Collection - scavenge");

		/*
		AtomicInteger scavct = scavCUMGCT.get("scav:CumulativeGCTimeInSeconds");
		if (scavct == null) {
			scavCUMGCT.put("scav:CumulativeGCTimeInSeconds", Metrics.gauge("scav:CumulativeGCTimeInSeconds", 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","JVM",
							"type","Garbage Collection - scavenge"),
					new AtomicInteger(
							timeInSeconds))
					);
		} else {
			scavct.set(timeInSeconds);
		}
		
		
		// Number of Garbage collection
	    Map<String,AtomicInteger>scavCUMNo = collectionscavengeMaps.get(getExecutionGroup());
		AtomicInteger scavno = scavCUMNo.get("scav:CumulativeNumberOfGCCollections");
		if (scavno == null) {
			scavCUMNo.put("scav:CumulativeNumberOfGCCollections", Metrics.gauge("scav:CumulativeNumberOfGCCollections", 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","JVM",
							"type","Garbage Collection - scavenge"),
					new AtomicInteger(
							no))
					);
		} else {
			scavno.set(no);
		}
		*/
	}

	/*
	 * JVM Garbage Collection - Global
	 */
	private void JVMGarbageCollectionGlob(Element jvm) {

		if (this._debug) {log.info("JVM Garbage Collection GLOB"); }
		Element gar = (Element) jvm;

		Map<String, AtomicInteger>flowMap 
			= collectionglobMaps.get(getExecutionGroup());
		if (flowMap == null) {
			Map<String, AtomicInteger>stats 
				= new HashMap<String,AtomicInteger>();
			collectionglobMaps.put(getExecutionGroup(), stats);
		}

		int timeInSeconds = Integer.parseInt(gar.getAttribute("CumulativeGCTimeInSeconds"));
		int no = Integer.parseInt(gar.getAttribute("CumulativeNumberOfGCCollections"));

		// Garbage collection time in seconds
	    Map<String,AtomicInteger>globCUMGCT = collectionscavengeMaps.get(getExecutionGroup());
		AtomicInteger s = globCUMGCT.get("glob:CumulativeGCTimeInSeconds");
		setCumulativeGCTimeInSeconds("glob:CumulativeGCTimeInSeconds",
				Integer.parseInt(gar.getAttribute("CumulativeGCTimeInSeconds")),
				globCUMGCT, s, "Garbage Collection - global");

	    Map<String,AtomicInteger>globCUMNo = collectionscavengeMaps.get(getExecutionGroup());
		AtomicInteger n = globCUMNo.get("glob:CumulativeNumberOfGCCollections");
		setCumulativeGCTimeInSeconds("glob:CumulativeNumberOfGCCollections",
				Integer.parseInt(gar.getAttribute("CumulativeNumberOfGCCollections")),
				globCUMNo, n, "Garbage Collection - global");

		/*
		// Garbage collection time in seconds
	    Map<String,AtomicInteger>globCUMGCT = collectionglobMaps.get(getExecutionGroup());
		AtomicInteger globct = globCUMGCT.get("glob:CumulativeGCTimeInSeconds");
		if (globct == null) {
			globCUMGCT.put("glob:CumulativeGCTimeInSeconds", Metrics.gauge("glob:CumulativeGCTimeInSeconds", 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","JVM",
							"type","Garbage Collection - global"),
					new AtomicInteger(
							timeInSeconds))
					);
		} else {
			globct.set(timeInSeconds);
		}

		// Number of Garbage collection
	    Map<String,AtomicInteger>globCUMNo = collectionglobMaps.get(getExecutionGroup());
		AtomicInteger globno = globCUMNo.get("glob:CumulativeNumberOfGCCollections");
		if (globno == null) {
			globCUMNo.put("glob:CumulativeNumberOfGCCollections", Metrics.gauge("glob:CumulativeNumberOfGCCollections", 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","JVM",
							"type","Garbage Collection - global"),
					new AtomicInteger(
							no))
					);
		} else {
			globno.set(no);
		}
		*/

	}
	
	// Initial memory
	private void setInitialMemoryInMB(int value, 
			Map<String, AtomicInteger> m, 
			AtomicInteger a,
			String t) {
		
		if (a == null) {
			m.put(new StringBuilder()
					.append(METRICPREFIX)
					.append("InitialMemoryInMB")
					.toString(), 
					Metrics.gauge(new StringBuilder()
					.append(METRICPREFIX)
					.append("InitialMemoryInMB")
					.toString(), 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","JVM",
							"type",t),
					new AtomicInteger(value))
					);
		} else {
			a.set(value);
		}
		
	}
	
	// Used memory
	private void setUsedMemoryInMB(int value, 
			Map<String, AtomicInteger> m, 
			AtomicInteger a,
			String t) {
		
		if (a == null) {
			m.put(new StringBuilder()
					.append(METRICPREFIX)
					.append("UsedMemoryInMB")
					.toString(),
					Metrics.gauge(new StringBuilder()
					.append(METRICPREFIX)
					.append("UsedMemoryInMB")
					.toString(), 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","JVM",
							"type",t),
					new AtomicInteger(
							value))
					);
		} else {
			a.set(value);
		}
		
	}

	// Max memory
	private void setMaxMemoryInMB(int value, 
			Map<String, AtomicInteger> m, 
			AtomicInteger a,
			String t) {
		
		if (a == null) {
			m.put(new StringBuilder()
					.append(METRICPREFIX)
					.append("MaxMemoryInMB")
					.toString(),
					Metrics.gauge(new StringBuilder()
					.append(METRICPREFIX)
					.append("MaxMemoryInMB")
					.toString(), 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","JVM",
							"type",t),
					new AtomicInteger(
							value))
					);
		} else {
			a.set(value);
		}
	}
	
	// Committed memory
	private void setCommittedMemoryInMB(int value, 
			Map<String, AtomicInteger> m, 
			AtomicInteger a,
			String t) {
			
		if (a == null) {
			m.put(new StringBuilder()
					.append(METRICPREFIX)
					.append("CommittedMemoryInMB")
					.toString(),
					Metrics.gauge(new StringBuilder()
					.append(METRICPREFIX)
					.append("CommittedMemoryInMB")
					.toString(), 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","JVM",
							"type",t),
					new AtomicInteger(value))
					);
		} else {
			a.set(value);
		}
		
	}

	// Initial memory
	private void setCumulativeGCTimeInSeconds(String g, 
			int value, 
			Map<String, AtomicInteger> m, 
			AtomicInteger a,
			String t) {

		if (a == null) {
			m.put(g,
					Metrics.gauge(new StringBuilder()
					.append(METRICPREFIX)
					.append(g)
					.toString(), 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","JVM",
							"type",t),
					new AtomicInteger(
							value))
					);
		} else {
			a.set(value);
		}		
		
	}
	
	
	private void ProcessODBC(NodeList jvmList) {

		for (int i = 0; i < jvmList.getLength(); i++) {
			Node j = jvmList.item(i);
			if (j.getNodeType() == Node.ELEMENT_NODE) {
				Element jvm = (Element) j;
				String name = jvm.getAttribute("name");
				switch (name.trim()) {
					case "summary": {
						ODBCSummary(jvm);
						break;
					}					
					default:{
						
					}
				}	
			}
		}	
		
	}

	/*
	 * ODBC summary
	 */
	private void ODBCSummary(Element jvm) {

		if (this._debug) {log.info("ODBC Summary"); }
		Element sum = (Element) jvm;

		Map<String, AtomicInteger>odbcMap 
				= ODBCSummaryMaps.get(getExecutionGroup());
		if (odbcMap == null) {
			Map<String, AtomicInteger>stats 
					= new HashMap<String,AtomicInteger>();
			ODBCSummaryMaps.put(getExecutionGroup(), stats);
		}

		// ExecuteSuccess
		Map<String,AtomicInteger>execSucc = ODBCSummaryMaps.get(getExecutionGroup());
		AtomicInteger isucc = execSucc.get(new StringBuilder()
				.append(METRICPREFIX)
				.append("ExecuteSuccess")
				.toString());
		setExecuteSuccess(Integer.parseInt(sum.getAttribute("ExecuteSuccess")),
				execSucc, isucc, "summary");
		
		// ExecuteFailure
		Map<String,AtomicInteger>execFail = ODBCSummaryMaps.get(getExecutionGroup());
		AtomicInteger ifail = execFail.get(new StringBuilder()
				.append(METRICPREFIX)
				.append("ExecuteFailure")
				.toString());
		setExecuteFailure(Integer.parseInt(sum.getAttribute("ExecuteFailure")),
				execFail, ifail, "summary");

		// Active connections
		Map<String,AtomicInteger>actConns = ODBCSummaryMaps.get(getExecutionGroup());
		AtomicInteger iconns = actConns.get(new StringBuilder()
				.append(METRICPREFIX)
				.append("ActiveConnections")
				.toString());
		setExecuteActConns(Integer.parseInt(sum.getAttribute("ActiveConnections")),
				actConns, iconns, "summary");

		// Closed connections
		Map<String,AtomicInteger>closedConns = ODBCSummaryMaps.get(getExecutionGroup());
		AtomicInteger iclsConns = closedConns.get(new StringBuilder()
				.append(METRICPREFIX)
				.append("ClosedConnections")
				.toString());
		setClosedConnections(Integer.parseInt(sum.getAttribute("ClosedConnections")),
				closedConns, iclsConns, "summary");

		// Connection error
		Map<String,AtomicInteger>connErrors = ODBCSummaryMaps.get(getExecutionGroup());
		AtomicInteger iconnErrors = closedConns.get(new StringBuilder()
				.append(METRICPREFIX)
				.append("ConnectionsErrors")
				.toString());
		setClosedConnections(Integer.parseInt(sum.getAttribute("ConnectionErrors")),
				connErrors, iconnErrors, "summary");
		
		
	}
	
	// ODBC Execute Success
	private void setExecuteSuccess(int value, 
			Map<String, AtomicInteger> m, 
			AtomicInteger a,
			String t) {
		
		if (a == null) {
			m.put(new StringBuilder()
					.append(METRICPREFIX)
					.append("ExecuteSuccess")
					.toString(), 
					Metrics.gauge(new StringBuilder()
					.append(METRICPREFIX)
					.append("ExecuteSuccess")
					.toString(), 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","ODBC",
							"type",t),
					new AtomicInteger(value))
					);
		} else {
			a.set(value);
		}
		
	}

	// ODBC Execute Failures
	private void setExecuteFailure(int value, 
			Map<String, AtomicInteger> m, 
			AtomicInteger a,
			String t) {
		
		if (a == null) {
			m.put(new StringBuilder()
					.append(METRICPREFIX)
					.append("ExecuteFailure")
					.toString(), 
					Metrics.gauge(new StringBuilder()
					.append(METRICPREFIX)
					.append("ExecuteFailure")
					.toString(), 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","ODBC",
							"type",t),
					new AtomicInteger(value))
					);
		} else {
			a.set(value);
		}
		
	}
	
	// ODBC Active connections
	private void setExecuteActConns(int value, 
			Map<String, AtomicInteger> m, 
			AtomicInteger a,
			String t) {
		
		if (a == null) {
			m.put(new StringBuilder()
					.append(METRICPREFIX)
					.append("ActiveConnections")
					.toString(), 
					Metrics.gauge(new StringBuilder()
					.append(METRICPREFIX)
					.append("ActiveConnections")
					.toString(), 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","ODBC",
							"type",t),
					new AtomicInteger(value))
					);
		} else {
			a.set(value);
		}
		
	}

	// ODBC Closed connections
	private void setClosedConnections(int value, 
			Map<String, AtomicInteger> m, 
			AtomicInteger a,
			String t) {
		
		if (a == null) {
			m.put(new StringBuilder()
					.append(METRICPREFIX)
					.append("ClosedConnections")
					.toString(), 
					Metrics.gauge(new StringBuilder()
					.append(METRICPREFIX)
					.append("ClosedConnections")
					.toString(), 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","ODBC",
							"type",t),
					new AtomicInteger(value))
					);
		} else {
			a.set(value);
		}
		
	}

	// ODBC ConnectionErrors
	private void setConnectionErrors(int value, 
			Map<String, AtomicInteger> m, 
			AtomicInteger a,
			String t) {
		
		if (a == null) {
			m.put(new StringBuilder()
					.append(METRICPREFIX)
					.append("ConnectionErrors")
					.toString(), 
					Metrics.gauge(new StringBuilder()
					.append(METRICPREFIX)
					.append("ConnectionErrors")
					.toString(), 
					Tags.of("brokerName", getBrokerName(),
							"executionGroup",getExecutionGroup(),
							"resource","ODBC",
							"type",t),
					new AtomicInteger(value))
					);
		} else {
			a.set(value);
		}
		
	}
	
	
}
