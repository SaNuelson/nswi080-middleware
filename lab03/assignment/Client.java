import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.util.*;

import javax.jms.*;
import javax.jms.Queue;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.w3c.dom.Text;

public class Client {
	
	/****	CONSTANTS	****/

	/****   PROPERTIES   ****/

	// name of the property specifying client's name
	public static final String CLIENT_NAME_PROPERTY = "clientName";

	// name of the property specifying goods' name
	public static final String GOODS_NAME_PROPERTY = "goodsName";

	public static final String ACCOUNT_NUMBER_PROPERTY = "accountNumber";

	public static final String GOODS_PRICE_PROPERTY = "goodsPrice";

	/****    SESSION    ****/

	// name of the topic for publishing offers
	public static final String OFFER_TOPIC = "Offers";

	// suffix of the queue for buying and selling from/to a specific client
	public static final String SALE_QUEUE = "SaleQueue";

	/****    MESSAGE TYPES    ****/

	// Sent from client to seller when requesting goods to buy
	public static final String BUY_ORDER_MESSAGE = "BUY_ORDER";

	// Sent from seller to client if requested goods are reserved/sold/unknown
	public static final String GOODS_UNAVAILABLE_MESSAGE = "GOOD_NOT_AVAILABLE";

	// Sent from seller to client upon successful reservation of ordered goods
	public static final String GOODS_RESERVED_MESSAGE = "GOODS_RESERVED";

	// Sent from seller to client upon successful transfer
	public static final String TRANSFER_RECEIVED_MESSAGE = "TRANSFER_RECEIVED";

	// Sent from seller to client upon failed payment
	public static final String GOODS_RELEASED_MESSAGE = "GOODS_RELEASED";

	// Sent from client to server to request its balance
	public static final String SHOW_BALANCE_MESSAGE = "SHOW_BALANCE";

	/****	PRIVATE VARIABLES	****/
	
	// client's unique name
	private String clientName;

	// client's account number
	private int accountNumber;
	
	// offered goods, mapped by name
	private Map<String, Goods> offeredGoods = new HashMap<String, Goods>();
	
	// available goods, mapped by seller's name 
	private Map<String, List<Goods>> availableGoods = new HashMap<String, List<Goods>>();
	
	// reserved goods, mapped by name of the goods
	private Map<String, Goods> reservedGoods = new HashMap<String, Goods>();
	
	// buyer's names, mapped by their account numbers
	private Map<Integer, String> reserverAccounts = new HashMap<Integer, String>();
	
	// buyer's reply destinations, mapped by their names
	private Map<String, Destination> reserverDestinations= new HashMap<String, Destination>();
	
	// connection to the broker
	private Connection conn;
	
	// session for user-initiated synchronous messages
	private Session clientSession;

	// session for listening and reacting to asynchronous messages
	private Session eventSession;

	// sender for the clientSession
	private MessageProducer clientSender;
	
	// sender for the eventSession
	private MessageProducer eventSender;

	// receiver of synchronous replies
	private MessageConsumer replyReceiver;
	
	// topic to send and receiver offers
	private Topic offerTopic;
	
	// queue for sending messages to bank
	private Queue toBankQueue;
	
	// queue for receiving synchronous replies
	private Queue replyQueue;


	
	// reader of lines from stdin
	private LineNumberReader in = new LineNumberReader(new InputStreamReader(System.in));
	
	/****	PRIVATE METHODS	****/
	
	/*
	 * Constructor, stores clientName, connection and initializes maps
	 */
	private Client(String clientName, Connection conn) {
		this.clientName = clientName;
		this.conn = conn;
		
		// generate some goods
		generateGoods();
	}
	
	/*
	 * Generate goods items
	 */
	private void generateGoods() {
		Random rnd = new Random();
		for (int i = 0; i < 10; ++i) {

			// added client prefix for better readability and debugging
			String clientPrefix = Arrays.stream(clientName.split(" ")).map(s -> s.substring(0, 1)).reduce("", (a, v) -> a + v);
			String name = clientPrefix + "-";

			for (int j = 0; j < 4; ++j) {
				char c = (char) ('A' + rnd.nextInt('Z' - 'A'));
				name += c;
			}
			
			offeredGoods.put(name, new Goods(name, rnd.nextInt(10000)));
		}
	}
	
	/*
	 * Set up all JMS entities, get bank account, publish first goods offer 
	 */
	private void connect() throws JMSException {
		// create two sessions - one for synchronous and one for asynchronous processing
		clientSession = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
		eventSession = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
		
		// create (unbound) senders for the sessions
		clientSender = clientSession.createProducer(null);
		eventSender = eventSession.createProducer(null);
		
		// create queue for sending messages to bank
		toBankQueue = clientSession.createQueue(Bank.BANK_QUEUE);
		// create a temporary queue for receiving messages from bank
		Queue fromBankQueue = eventSession.createTemporaryQueue();

		// temporary receiver for the first reply from bank
		// note that although the receiver is created within a different session
		// than the queue, it is OK since the queue is used only within the
		// client session for the moment
		MessageConsumer tmpBankReceiver = clientSession.createConsumer(fromBankQueue);        
		
		// start processing messages
		conn.start();
		
		// request a bank account number
		Message msg = eventSession.createTextMessage(Bank.NEW_ACCOUNT_MSG);
		msg.setStringProperty(CLIENT_NAME_PROPERTY, clientName);
		// set ReplyTo that Bank will use to send me reply and later transfer reports
		msg.setJMSReplyTo(fromBankQueue);
		clientSender.send(toBankQueue, msg);
		
		// get reply from bank and store the account number
		TextMessage reply = (TextMessage) tmpBankReceiver.receive();
		accountNumber = Integer.parseInt(reply.getText());
		System.out.println("Account number: " + accountNumber);
		
		// close the temporary receiver
		tmpBankReceiver.close();
		
		// temporarily stop processing messages to finish initialization
		conn.stop();
		
		/* Processing bank reports */
		
		// create consumer of bank reports (from the fromBankQueue) on the event session
		MessageConsumer bankReceiver = eventSession.createConsumer(fromBankQueue);
		
		// set asynchronous listener for reports, using anonymous MessageListener
		// which just calls our designated method in its onMessage method
		bankReceiver.setMessageListener(new MessageListener() {
			@Override
			public void onMessage(Message msg) {
				try {
					processBankReport(msg);
				} catch (JMSException e) {
					e.printStackTrace();
				}
			}
		});

		// TODO finish the initialization
		
		/* Step 1: Processing offers */
		
		// create a topic both for publishing and receiving offers
		// hint: Sessions have a createTopic() method
		offerTopic = eventSession.createTopic(OFFER_TOPIC);
		
		// create a consumer of offers from the topic using the event session
		var offerConsumer = eventSession.createConsumer(offerTopic);
		
		// set asynchronous listener for offers (see above how it can be done)
		// which should call processOffer()
		offerConsumer.setMessageListener(new MessageListener() {
			@Override
			public void onMessage(Message msg) {
				try {
					processOffer(msg);
				} catch (JMSException e) {
					e.printStackTrace();
				}
			}
		});
		
		/* Step 2: Processing sale requests */
		
		// create a queue for receiving sale requests (hint: Session has createQueue() method)
		// note that Session's createTemporaryQueue() is not usable here, the queue must have a name
		// that others will be able to determine from clientName (such as clientName + "SaleQueue")
		var saleRequestQueue = eventSession.createQueue(clientName + SALE_QUEUE);
		    
		// create consumer of sale requests on the event session
		var saleRequestConsumer = eventSession.createConsumer(saleRequestQueue);
		    
		// set asynchronous listener for sale requests (see above how it can be done)
		// which should call processSale()
		saleRequestConsumer.setMessageListener(new MessageListener() {
			@Override
			public void onMessage(Message msg) {
				try {
					processSale(msg);
				} catch (JMSException e) {
					e.printStackTrace();
				}
			}
		});
		
		// end TODO
		
		// create temporary queue for synchronous replies
		replyQueue = clientSession.createTemporaryQueue();
		
		// create synchronous receiver of the replies
		replyReceiver = clientSession.createConsumer(replyQueue);
		
		// restart message processing
		conn.start();
		
		// send list of offered goods
		publishGoodsList(clientSender, clientSession);
	}

	/*
	 * Publish a list of offered goods
	 * Parameter is an (unbound) sender that fits into current session
	 * Sometimes we publish the list on user's request, sometimes we react to an event
	 */
	private void publishGoodsList(MessageProducer sender, Session session) throws JMSException {
		// TODO
		
		// create a message (of appropriate type) holding the list of offered goods
		// which can be created like this: new ArrayList<Goods>(offeredGoods.values())
		var offersMessage = session.createObjectMessage(new ArrayList<Goods>(offeredGoods.values()));
		offersMessage.setStringProperty(CLIENT_NAME_PROPERTY, clientName);
		
		// don't forget to include the clientName in the message so other clients know
		// who is sending the offer - see how connect() does it when sending message to bank

		// send the message using the sender passed as parameter
		sender.send(offerTopic, offersMessage);
	}
	
	/*
	 * Send empty offer and disconnect from the broker 
	 */
	private void disconnect() throws JMSException {
		// delete all offered goods
		offeredGoods.clear();
		
		// send the empty list to indicate client quit
		publishGoodsList(clientSender, clientSession);
		
		// close the connection to broker
		conn.close();
	}
	
	/*
	 * Print known goods that are offered by other clients
	 */
	private void list() {
		System.out.println("Available goods (name: price):");
		// iterate over sellers
		for (String sellerName : availableGoods.keySet()) {
			System.out.println("From " + sellerName);
			// iterate over goods offered by a seller
			for (Goods g : availableGoods.get(sellerName)) {
				System.out.println("  " + g);
			}
		}
	}
	
	/*
	 * Main interactive user loop
	 */
	private void loop() throws IOException, JMSException {
		// first connect to broker and setup everything
		connect();
		
		loop:
		while (true) {
			System.out.println("\nAvailable commands (type and press enter):");
			System.out.println(" l - list available goods");
			System.out.println(" p - publish list of offered goods");
			System.out.println(" b - buy goods");
			System.out.println(" h - haggle (try to buy for half the price)");
			System.out.println(" s - show balance");
			System.out.println(" q - quit");
			// read first character
			int c = in.read();
			// throw away rest of the buffered line
			while (in.ready()) in.read();
			switch (c) {
				case 'q':
					disconnect();
					break loop;
				case 'b':
					buy(false);
					break;
				case 'h':
					buy(true);
				case 'l':
					list();
					break;
				case 's':
					balance();
					break;
				case 'p':
					publishGoodsList(clientSender, clientSession);
					System.out.println("List of offers published");
					break;
				case '\n':
				default:
					break;
			}
		}
	}

	private void balance() throws JMSException {
		var requestMessage = eventSession.createTextMessage(SHOW_BALANCE_MESSAGE);
		requestMessage.setStringProperty(CLIENT_NAME_PROPERTY, clientName);
		requestMessage.setJMSReplyTo(replyQueue);

		eventSender.send(toBankQueue, requestMessage);

		var responseMessage = (TextMessage) replyReceiver.receive();
		if (Bank.REPORT_BALANCE_MESSAGE.equals(responseMessage.getText())) {
			System.out.println("Current balance is " + responseMessage.getIntProperty(Bank.BALANCE_PROPERTY));
		}
		else {
			System.out.println("Received unknown balance response message ("+responseMessage.getText()+").");
		}
	}

	/*
	 * Perform buying of goods
	 */
	private void buy(boolean haggle) throws IOException, JMSException {
		// get information from the user
		System.out.println("Enter seller name:");
		String sellerName = in.readLine();
		System.out.println("Enter goods name:");
		String goodsName = in.readLine();

		// check if the seller exists
		List<Goods> sellerGoods = availableGoods.get(sellerName);
		if (sellerGoods == null) {
			System.out.println("Seller does not exist: " + sellerName);
			return;
		}
		
		// TODO
		
		// First consider what message types clients will use for communicating a sale
		// we will need to transfer multiple values (of String and int) in each message 
		// MapMessage? ObjectMessage? TextMessage with extra properties?
		
		/* Step 1: send a message to the seller requesting the goods */
		
		// create local reference to the seller's queue
		// similar to Step 2 in connect() but using sellerName instead of clientName
		var sellerQueue = clientSession.createQueue(sellerName + SALE_QUEUE);
		
		// create message requesting sale of the goods
		// includes: clientName, goodsName, accountNumber
		// also include reply destination that the other client will use to send reply (replyQueue)
		// how? see how connect() uses SetJMSReplyTo()
		var orderMessage = clientSession.createTextMessage(BUY_ORDER_MESSAGE);
		orderMessage.setStringProperty(CLIENT_NAME_PROPERTY, clientName);
		orderMessage.setStringProperty(GOODS_NAME_PROPERTY, goodsName);
		orderMessage.setIntProperty(ACCOUNT_NUMBER_PROPERTY, accountNumber);
		orderMessage.setJMSReplyTo(replyQueue);

		// send the message (with clientSender)
		clientSender.send(sellerQueue, orderMessage);
		System.out.printf("Sending a reservation request (%s) to %s.%n", sellerName, orderMessage.getStringProperty(CLIENT_NAME_PROPERTY));
		
		/* Step 2: get seller's response and process it */
		
		// receive the reply (synchronously, using replyReceiver)
		TextMessage replyMessage = (TextMessage) replyReceiver.receive();
		var replyType = replyMessage.getText();
		if (GOODS_UNAVAILABLE_MESSAGE.equals(replyType)) {
			System.out.println("Seller replies the requested item is not available.");
			return;
		}
		else if (GOODS_RESERVED_MESSAGE.equals(replyType)) {
			System.out.println("Seller replies the requested item is reserved.");
		}
		else {
			System.out.println("Seller reply has an unknown format");
			return;
		}

		// parse the reply (depends on your selected message format)
		// distinguish between "sell denied" and "sell accepted" message
		// in case of "denied", report to user and return from this method
		// in case of "accepted"
		// - obtain seller's account number and price to pay
		int price = replyMessage.getIntProperty(GOODS_PRICE_PROPERTY);

		if (haggle)
			price /= 2;

		int sellerAccount = replyMessage.getIntProperty(ACCOUNT_NUMBER_PROPERTY);

		/* Step 3: send message to bank requesting money transfer */
		
		// create message ordering the bank to send money to seller
		MapMessage bankMsg = clientSession.createMapMessage();
		bankMsg.setStringProperty(CLIENT_NAME_PROPERTY, clientName);
		bankMsg.setInt(Bank.ORDER_TYPE_KEY, Bank.ORDER_TYPE_SEND);
		bankMsg.setInt(Bank.ORDER_RECEIVER_ACC_KEY, sellerAccount);
		bankMsg.setInt(Bank.AMOUNT_KEY, price);
		
		System.out.println("Sending $" + price + " to account " + sellerAccount);
		
		// send message to bank
		clientSender.send(toBankQueue, bankMsg);

		/* Step 4: wait for seller's sale confirmation */
		
		// receive the confirmation, similar to Step 2
		var sellerReply = (TextMessage) replyReceiver.receive();

		// parse message and verify it's confirmation message
		if (GOODS_RELEASED_MESSAGE.equals(sellerReply.getText())) {
			System.out.println("Buy order failed.");
		}
		else if (TRANSFER_RECEIVED_MESSAGE.equals(sellerReply.getText())) {
			System.out.println("Buy order successful.");
		}
		else {
			System.out.println("Unknown message response from the seller.");
		}
		
		// report successful sale to the user
	}
	
	/*
	 * Process a message with goods offer
	 */
	private void processOffer(Message msg) throws JMSException {
		// TODO

		// parse the message, obtaining sender's name and list of offered goods

		if (!(msg instanceof ObjectMessage)) {
			System.out.println("Incoming offer message is not an ObjectMessage");
			return;
		}
		ObjectMessage message = (ObjectMessage) msg;

		if (message.getStringProperty(CLIENT_NAME_PROPERTY) == null) {
			System.out.println("Incoming offer message does not contain client name property");
			return;
		}

		String senderClientName = message.getStringProperty(CLIENT_NAME_PROPERTY);

		// should ignore messages sent from myself
		// if (clientName.equals(sender)) ...
		if (clientName.equals(senderClientName)) {
			return;
		}

		ArrayList<Goods> senderOffers;
		try {
			senderOffers = (ArrayList<Goods>) message.getObject();
		} catch (ClassCastException ignored) {
			System.out.println("Incoming offer message body is not an arraylist of goods");
			return;
		}
		
		// store the list into availableGoods (replacing any previous offer)
		// empty list means disconnecting client, remove it from availableGoods completely
		if (senderOffers.isEmpty()) {
			availableGoods.remove(senderClientName);
		} else {
			availableGoods.put(senderClientName, senderOffers);
		}
	}
	
	/*
	 * Process message requesting a sale
	 */
	private void processSale(Message msg) throws JMSException {
		// TODO
		
		/* Step 1: parse the message */
		
		// distinguish that it's the sale request message
		if (!(msg instanceof TextMessage)) {
			System.out.println("Incoming buyer message is not of type Text Message");
			return;
		}
		TextMessage message = (TextMessage) msg;

		if (!BUY_ORDER_MESSAGE.equals(message.getText())) {
			System.out.println("Incoming buyer message does not have a buyer message text ("+message.getText()+")");
			return;
		}

		// obtain buyer's name (buyerName), goods name (goodsName) , buyer's account number (buyerAccount)
		var buyerName = message.getStringProperty(CLIENT_NAME_PROPERTY);
		var goodsName = message.getStringProperty(GOODS_NAME_PROPERTY);
		var buyerAccount = message.getIntProperty(ACCOUNT_NUMBER_PROPERTY);
		
		// also obtain reply destination (buyerDest)
		// how? see for example Bank.processTextMessage()
		var replyDestination = message.getJMSReplyTo();

		/* Step 2: decide what to do and modify data structures accordingly */
		
		// check if we still offer this goods
		Goods goods = offeredGoods.get(goodsName);
		if (goods == null) {
			System.out.println("Incoming buyer message requests item which is not offered.");

			var declineMessage = clientSession.createTextMessage(GOODS_UNAVAILABLE_MESSAGE);
			clientSender.send(replyDestination, declineMessage);
			return;
		}

		// if yes, we should remove it from offeredGoods and publish new list
		// also it's useful to create a list of "reserved goods" together with buyer's information
		// such as name, account number, reply destination
		offeredGoods.remove(goodsName);
		reservedGoods.put(buyerName, goods);
		reserverAccounts.put(buyerAccount, buyerName);
		reserverDestinations.put(buyerName, replyDestination);
		
		/* Step 3: send reply message */
		
		// prepare reply message (accept or deny)
		// accept message includes: my account number (accountNumber), price (goods.price)
		var acceptMessage = clientSession.createTextMessage(GOODS_RESERVED_MESSAGE);
		acceptMessage.setStringProperty(CLIENT_NAME_PROPERTY, clientName);
		acceptMessage.setIntProperty(ACCOUNT_NUMBER_PROPERTY, accountNumber);
		acceptMessage.setIntProperty(GOODS_PRICE_PROPERTY, goods.price);
		
		// send reply
		clientSender.send(replyDestination, acceptMessage);
	}
	
	/*
	 * Process message with (transfer) report from the bank
	 */
	private void processBankReport(Message msg) throws JMSException {
		/* Step 1: parse the message */
		
		// Bank reports are sent as MapMessage
		if (msg instanceof MapMessage) {
			MapMessage mapMsg = (MapMessage) msg;
			// get report number
			int cmd = mapMsg.getInt(Bank.REPORT_TYPE_KEY);
			if (cmd == Bank.REPORT_TYPE_RECEIVED) {
				// get account number of sender and the amount of money sent
				int buyerAccount = mapMsg.getInt(Bank.REPORT_SENDER_ACC_KEY);
				int amount = mapMsg.getInt(Bank.AMOUNT_KEY);
				
				// match the sender account with sender
				String buyerName = reserverAccounts.get(buyerAccount);
				
				// match the reserved goods
				Goods g = reservedGoods.get(buyerName);
				
				System.out.println("Received $" + amount + " from " + buyerName);
				
				/* Step 2: decide what to do and modify data structures accordingly */
				
				// did he pay enough?
				if (amount >= g.price) {
					// get the buyer's destination
					Destination buyerDest = reserverDestinations.get(buyerName);

					// remove the reserved goods and buyer-related information
					reserverDestinations.remove(buyerName);
					reserverAccounts.remove(buyerAccount);
					reservedGoods.remove(buyerName);
					
					/* TODO Step 3: send confirmation message */

					// prepare sale confirmation message
					// includes: goods name (g.name)
					var finalMessage = clientSession.createTextMessage(TRANSFER_RECEIVED_MESSAGE);
					finalMessage.setStringProperty(GOODS_NAME_PROPERTY, g.name);
					
					// send reply (destination is buyerDest)
					clientSender.send(buyerDest, finalMessage);
				} else {
					// we received less money than expected

					Destination buyerDest = reserverDestinations.get(buyerName);

					// remove reserved goods and return to offers
					reserverDestinations.remove(buyerName);
					reserverAccounts.remove(buyerAccount);

					var reofferedGoods = reservedGoods.remove(buyerName);
					offeredGoods.put(reofferedGoods.name, reofferedGoods);

					System.out.println("Received incorrect amount of money from " + buyerName);

					var releaseMessage = clientSession.createTextMessage(GOODS_RELEASED_MESSAGE);
					releaseMessage.setStringProperty(GOODS_NAME_PROPERTY, g.name);

					// notify seller of failed transaction
					clientSender.send(buyerDest, releaseMessage);

					// additionally request bank to return the money to buyer
					MapMessage bankMsg = clientSession.createMapMessage();
					bankMsg.setStringProperty(CLIENT_NAME_PROPERTY, clientName);
					bankMsg.setInt(Bank.ORDER_TYPE_KEY, Bank.ORDER_TYPE_SEND);
					bankMsg.setInt(Bank.ORDER_RECEIVER_ACC_KEY, buyerAccount);
					bankMsg.setInt(Bank.AMOUNT_KEY, amount);
					bankMsg.setBoolean(Bank.SILENT_TRANSACTION_PROPERTY, true);

					System.out.println("Refunding $" + amount + " to account " + buyerAccount);

					// send message to bank
					clientSender.send(toBankQueue, bankMsg);
				}
			}
			else if(cmd == Bank.REPORT_TYPE_FAILED) {
				// get account number of sender and the amount of money sent
				int buyerAccount = mapMsg.getInt(Bank.REPORT_SENDER_ACC_KEY);

				// match the sender account with sender
				String buyerName = reserverAccounts.get(buyerAccount);

				// match the reserved goods
				Goods g = reservedGoods.get(buyerName);

				Destination buyerDest = reserverDestinations.get(buyerName);

				// remove reserved goods and return to offers
				reserverDestinations.remove(buyerName);
				reserverAccounts.remove(buyerAccount);

				var reofferedGoods = reservedGoods.remove(buyerName);
				offeredGoods.put(reofferedGoods.name, reofferedGoods);

				System.out.println("Did not receive money from " + buyerName);

				var releaseMessage = clientSession.createTextMessage(GOODS_RELEASED_MESSAGE);
				releaseMessage.setStringProperty(GOODS_NAME_PROPERTY, g.name);

				clientSender.send(buyerDest, releaseMessage);
			}
			else {
				System.out.println("Received unknown MapMessage:\n: " + msg);
			}
		} else {
			System.out.println("Received unknown message:\n: " + msg);
		}
	}
	
	/**** PUBLIC METHODS ****/
	
	/*
	 * Main method, creates client instance and runs its loop
	 */
	public static void main(String[] args) {

		if (args.length != 1) {
			System.err.println("Usage: ./client <clientName>");
			return;
		}
		
		// create connection to the broker.
		Connection connection = null;
		try {
			ActiveMQConnectionFactory connectionFactory = new ActiveMQConnectionFactory("tcp://localhost:61616");
			connectionFactory.setTrustAllPackages(true); // added to allow ArrayLists being deserialized
			connection = connectionFactory.createConnection();
			// create instance of the client
			Client client = new Client(args[0], connection);
			
			// perform client loop
			client.loop();
		}
		catch (Exception e) {
			e.printStackTrace();
		}
		finally {
			try {
				// always close the connection
				connection.close();
			} catch (Throwable ignore) {
				// ignore errors during close
			}
		}
	}
}
