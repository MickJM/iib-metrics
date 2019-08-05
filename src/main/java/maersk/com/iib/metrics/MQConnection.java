package maersk.com.iib.metrics;

import java.util.Hashtable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * MQConnection object to connect / disconnect from IBM MQ
 * 
 */
import javax.annotation.PreDestroy;
import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
//import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageListener;
//import javax.jms.MessageListener;
//import javax.jms.MessageProducer;
//import javax.jms.Queue;
import javax.jms.Session;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ibm.mq.MQException;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.jms.MQConnectionFactory;
//import com.ibm.msg.client.jms.JmsFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;

import maersk.com.iib.mq.MQListener;

@Component
public class MQConnection {

	private Logger log = Logger.getLogger(this.getClass());

	/*
	 * Parameters for connecting to a queue manager, using non-ssl
	 */
	@Value("${ibm.mq.connName}")	
	private String connName;
	@Value("${ibm.mq.queueManager}")
	private String queueManager;
	@Value("${ibm.mq.channel}")	
	private String channel;
	
	@Value("${ibm.mq.user}")	
	private String userId;
	@Value("${ibm.mq.password}")	
	private String password;
	@Value("${ibm.mq.queue}")	
	private String queueName;

	private String hostName;
	private int port;

	//
	@Value("${ibm.mq.iibNode}")	
	private String iibName;
	@Value("${ibm.mq.event.resetValue}")	
	private int resetValue;
	private int reset;
	
	/*
	 * JMS MQ Objects
	 */
    private Connection connection = null;
    private Session session = null;
    private Destination destination = null;
    private MessageConsumer consumer = null;
    private MQListener listener = null;
    //
    private int noOfTimesInvoked = 0;
    		
	//@Autowired
	//private Environment environ = null;
	
    /*
     * Create a JMS connection to a queue manager
     */
    @Bean
	public MQConnectionFactory createMQConnection() throws Exception, JMSException, MQException {

    	log.info("Creating MQ connection");
    	GetEnvironmentVariables();
    	
		MQConnectionFactory cf = new MQConnectionFactory();
        cf.setHostName(this.hostName);
        cf.setPort(this.port);
        cf.setQueueManager(this.queueManager);
        cf.setChannel(this.channel);
        
		cf.setAppName("IIB-METRICS");
		cf.setTransportType(WMQConstants.WMQ_CM_CLIENT);
		cf.setClientReconnectOptions(WMQConstants.WMQ_CLIENT_RECONNECT);
		cf.setClientReconnectTimeout(600);
		
	    log.info("JMS Creating connection.");
	    this.connection = cf.createConnection(this.userId, this.password);	   
	    log.info("JMS Connection created.");
	    
	 //   this.session = this.connection.createSession(true, Session.SESSION_TRANSACTED);
	    log.info("Creating JMS connection.");
	    this.session = this.connection.createSession(true, Session.AUTO_ACKNOWLEDGE);	    
	    log.info("JMS Session created.");
	    
	    /*
	     * Connect to a queue and allocate an MQListener object 
	     */
	   	this.destination = this.session.createQueue(this.queueName);
	    this.consumer = this.session.createConsumer(this.destination);
	    this.listener = new MQListener(this.session, this.iibName);
	   	this.consumer.setMessageListener(this.listener);
	   	
    	// Start the connection
    	this.connection.start();

    	log.info("MQ connection created and service listener started");

	    return cf;
		    
	}

        
	/**
	 * Get MQ details from environment variables
	 */
	private void GetEnvironmentVariables() throws Exception {
		
		/*
		 * ALL parameter are passed in the application.yaml file ...
		 *    These values can be overrrided using an application-???.yaml file per environment
		 *    ... or passed in on the command line
		 */
		
		//this.reset = Integer.parseInt(this.resetValue);
		
		// Split the host and port number from the connName ... host(port)
		if (!this.connName.equals("")) {
			Pattern pattern = Pattern.compile("^([^()]*)\\(([^()]*)\\)(.*)$");
			Matcher matcher = pattern.matcher(this.connName);	
			if (matcher.matches()) {
				this.hostName = matcher.group(1).trim();
				this.port = Integer.parseInt(matcher.group(2).trim());
			} else {
				log.error("While attempting to connect to a queue manager, the connName is invalid ");
				System.exit(1);				
			}
		} else {
			log.error("While attempting to connect to a queue manager, the connName is missing  "); 
			System.exit(1);
			
		}

		// if no use, for get it ...
		if (this.userId == null) {
			return;
		}
		
		if (!this.userId.equals("")) {
			if ((this.userId.equals("mqm") || (this.userId.equals("MQM")))) {
				log.error("The MQ channel USERID must not be running as 'mqm' ");
				System.exit(1);
			}
		} else {
			this.userId = null;
			this.password = null;
		}
	

		
		/*
		this.hostName = System.getenv("HOST");
		this.queueManager = System.getenv("QMGR");
		this.channel = System.getenv("CHANNEL");
		this.port = Integer.parseInt(System.getenv("PORT"));
		this.userId = System.getenv("USERID");
		this.password = System.getenv("PASSWORD");
		this.iibName = System.getenv("IIBBROKER");
		this.queueName = System.getenv("STATSQUEUE");
		
		// if the reset value isn't set, default to 5
		try {
			this.resetValue = Integer.parseInt(System.getenv("RESETVALUE"));
		} catch (Exception e) {
			log.warn("Default value of 5 being used");
			this.resetValue = 5;
		}
		*/
		
	}

	// Scheduled method which checks if the metrics are being updated
	// ... the 'onMessage' method is invoked when a message is received
	//
	@Scheduled(fixedDelay=10000)
    public void Scheduler() {

		this.noOfTimesInvoked++;
		
		if (this.listener != null) {
			
			boolean b = this.listener.getUpdating();
			if ((this.noOfTimesInvoked % this.resetValue) == 0) {
				if (b) {
					this.noOfTimesInvoked = 0;
					this.listener.setUpdating(false);
				} else {
					this.listener.ResetMetrics();
				}
			}
		}
		
	}

	
    @PreDestroy
    public void CloseQMConnection() {
    	
    	try {
	    	if (this.consumer != null) {
	    		log.info("Closing consumer.");	        	
	    		this.consumer.close();
	    	}	
	    	if (this.connection != null) {
	    		log.info("Closing connection.");
	    		this.connection.close();
	    	}
	    	if (this.session != null) {
	    		log.info("Closing session.");
	    		this.session.close();
	    	}
    	} catch (Exception e) {
    		// do nothing
    	}
    }
    
}

