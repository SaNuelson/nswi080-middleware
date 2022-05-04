import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IExecutorService;
import com.hazelcast.map.IMap;
import common.*;
import hazel.FetchDocTask;
import hazel.processor.*;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static common.Constants.*;

public class Client {
	// Reader for user input
	private final LineNumberReader in = new LineNumberReader(new InputStreamReader(System.in));
	// Connection to the cluster
	private final HazelcastInstance hazelcast;
	// The name of the user
	private final String userName;
	// Do not keep any other state here - all data should be in the cluster

	/**
	 * Create a client for the specified user.
	 * @param userName user name used to identify the user
	 */
	public Client(String userName) {
		this.userName = userName;

		ClientConfig config = new ClientConfig();
		hazelcast = HazelcastClient.newHazelcastClient(config);
	}

	/**
	 * Disconnect from the Hazelcast cluster.
	 */
	public void disconnect() {
		// Disconnect from the Hazelcast cluster
		hazelcast.shutdown();
	}

	/**
	 * Custom debugging method to start up some testing data
	 */
	public void setupDemo() throws ExecutionException, InterruptedException {
		System.out.println("Client setting up demo environment...");
		Random rnd = new Random();
		IMap<String, Document> docMap = hazelcast.getMap(DOC_CACHE_MAP);
		IMap<String, List<String>> faveMap = hazelcast.getMap(FAVE_DOCS_MAP);
		IMap<String, List<Comment>> commMap = hazelcast.getMap(DOC_COMMS_MAP);
		IExecutorService executor = hazelcast.getExecutorService(DEFAULT_EXECUTOR);

		List<String> docNames = Arrays.asList("abc", "def", "ghi", "jkl", "mno", "pqr", "stu", "vwx");

		Collections.shuffle(docNames);
		for (String docName : docNames.subList(0, 3)) {
			System.out.printf("Publishing %s...%n",docName);
			executor.submit(new FetchDocTask(userName, docName)).get();

			boolean isFave = rnd.nextBoolean();
			if (isFave) {
				System.out.println("... that's a good one.");
				faveMap.executeOnKey(docName, new AddToFavesProcessor(userName, docName));
			}

			int comCount = rnd.nextInt(3);
			System.out.printf("Ranting on %s...%n",docName);
			for (int i = 0; i < comCount; i++) {
				Comment comm = new Comment("Comment #" + i, userName);
				commMap.executeOnKey(docName, new AddCommentProcessor(docName, comm));
			}
		}
		System.out.println("All done, test data is up.");
	}

	/**
	 * Read a name of a document,
	 * select it as the current document of the user
	 * and show the document content.
	 */
	private void showCommand() throws IOException {
		System.out.println("Enter document name:");
		String documentName = in.readLine();

		// Currently, the document is generated directly on the client
		// TODO: change it, so that the document is generated in the cluster and cached
		// TODO: Set the current selected document for the user
		// TODO: Get the document (from the cache, or generated)
		// TODO: Increment the view count
		Future<Document> docRequest = hazelcast
				.getExecutorService(DEFAULT_EXECUTOR)
				.submit(new FetchDocTask(userName, documentName));

		Document document = null;
		try {
			document = docRequest.get();
		} catch (Exception e) {
			e.printStackTrace();
		}

		if (document == null) {
			System.out.println("UNEXPECTED: Failed to fetch document.");
			return;
		}

		// Show the document content
		System.out.println("The document is:");
		System.out.println(document.getContent());
	}

	/**
	 * Show the next document in the list of favorites of the user.
	 * Select the next document, so that running this command repeatedly
	 * will cyclically show all favorite documents of the user.
	 */
	private void nextFavoriteCommand() {
		// TODO: Select the next document form the list of favorites
		// TODO: Increment the view count, get the document (from the cache, or generated) and show the document content

		// Find the last viewed document
		IMap<String, String> lastViewedMap = hazelcast.getMap(LAST_DOCS_MAP);
		String lastDocName = lastViewedMap.executeOnKey(userName, new GetUserLastViewedProcessor(userName));
		if (lastDocName == null) {
			System.out.println("UNEXPECTED: Failed to retrieve name of the last viewed document.");
			return;
		}

		// Try to find next in favourites (possibly the same one if only that one is favourited)
		IMap<String, List<String>> faveMap = hazelcast.getMap(FAVE_DOCS_MAP);
		String nextDocName = faveMap.executeOnKey(userName, new GetNextFaveProcessor(userName, lastDocName));
		if (nextDocName == null) {
			System.out.printf("Your last viewed document (%s) is not your favourite.%n", lastDocName);
			System.out.println("Try using 'l' command to see your favourites.");
			return;
		}

		// Fetch the document contents
		Future<Document> docRequest = hazelcast
				.getExecutorService(DEFAULT_EXECUTOR)
				.submit(new FetchDocTask(userName, nextDocName));

		Document document = null;
		try {
			document = docRequest.get();
		} catch (Exception e) {
			e.printStackTrace();
		}

		// Show the document content
		System.out.println("Your next favourite document is:");
		System.out.println(document.getContent());
	}

	/**
	 * Add the current selected document name to the list of favorite documents of the user.
	 * If the list already contains the document name, do nothing.
	 */
	private void addFavoriteCommand() {
		// TODO: Add the name of the selected document to the list of favorites

		// Find the last viewed document
		IMap<String, String> lastViewedMap = hazelcast.getMap(LAST_DOCS_MAP);
		String lastDocName = lastViewedMap.executeOnKey(userName, new GetUserLastViewedProcessor(userName));
		if (lastDocName == null) {
			System.out.println("UNEXPECTED: Failed to retrieve name of the last viewed document.");
			return;
		}

		// Try adding it (possibly failing if it's already present)
		IMap<String, List<String>> faveMap = hazelcast.getMap(FAVE_DOCS_MAP);
		boolean success = faveMap.executeOnKey(userName, new AddToFavesProcessor(userName, lastDocName));
		if (!success) {
			System.out.printf("Document %s already in favorites%n", lastDocName);
		}
		else {
			System.out.printf("Added %s to favorites%n", lastDocName);
		}
	}

	/**
	 * Remove the current selected document name from the list of favorite documents of the user.
	 * If the list does not contain the document name, do nothing.
	 */
	private void removeFavoriteCommand(){
		// TODO: Remove the name of the selected document from the list of favorites

		// Find the last viewed document
		IMap<String, String> lastViewedMap = hazelcast.getMap(LAST_DOCS_MAP);
		String lastDocName = lastViewedMap.executeOnKey(userName, new GetUserLastViewedProcessor(userName));
		if (lastDocName == null) {
			System.out.println("UNEXPECTED: Failed to retrieve name of the last viewed document.");
			return;
		}

		// Try removing it
		IMap<String, List<String>> faveMap = hazelcast.getMap(FAVE_DOCS_MAP);
		boolean success = faveMap.executeOnKey(userName, new RemoveFromFavesProcessor(userName, lastDocName));
		if (!success) {
			System.out.printf("Document %s is not your favorite%n", lastDocName);
		}
		else {
			System.out.printf("Removed %s from favorites%n", lastDocName);
		}
	}

	/**
	 * Add the current selected document name to the list of favorite documents of the user.
	 * If the list already contains the document name, do nothing.
	 */
	private void listFavoritesCommand() {

		IMap<String, List<String>> faveMap = hazelcast.getMap(FAVE_DOCS_MAP);
		List<String> favourites = faveMap.executeOnKey(userName, new GetAllFavesProcessor(userName));

		if (favourites.size() == 0) {
			System.out.println("Your list of favorite documents is empty.");
		}
		else {
			// TODO: Get the list of favorite documents of the user
			// Print the list of favorite documents
			System.out.println("Your list of favorite documents:");
			for(String favoriteDocumentName: favourites)
				System.out.println(favoriteDocumentName);
		}

	}

	/**
	 * Show the view count and comments of the current selected document.
	 */
	private void infoCommand(){

		// TODO: Get the view count and list of comments of the selected document
		// Find the last viewed document
		IMap<String, String> lastViewedMap = hazelcast.getMap(LAST_DOCS_MAP);
		String lastDocName = lastViewedMap.executeOnKey(userName, new GetUserLastViewedProcessor(userName));
		if (lastDocName == null) {
			System.out.println("UNEXPECTED: Failed to retrieve name of the last viewed document.");
			return;
		}

		// Find its view count
		IMap<String, Integer> viewsMap = hazelcast.getMap(DOC_VIEWS_MAP);
		int viewCount = viewsMap.executeOnKey(lastDocName, new GetViewsProcessor(lastDocName));
		if (viewCount < 0) {
			System.out.println("UNEXPECTED: Failed to retrieve view count for a document.");
			return;
		}

		// Get comments
		IMap<String, List<Comment>> commentMap = hazelcast.getMap(DOC_COMMS_MAP);
		List<Comment> comments = commentMap.executeOnKey(lastDocName, new GetAllCommentsProcessor(lastDocName));
		if (comments == null) {
			System.out.println("UNEXPECTED: Failed to retrieve comments for a document.");
			return;
		}


		// Print the information
		System.out.printf("Info about %s:%n", lastDocName);
		System.out.printf("Viewed %d times.%n", viewCount);
		System.out.printf("Comments (%d):%n", comments.size());
		for(Comment comment: comments)
			System.out.println(comment);
	}
	/**
	 * Add a comment about the current selected document.
	 */
	private void commentCommand() throws IOException{
		System.out.println("Enter comment text:");
		String commentText = in.readLine();

		// TODO: Add the comment to the list of comments of the selected document
		Comment comment = new Comment(commentText, userName);

		// Find the last viewed document
		IMap<String, String> lastViewedMap = hazelcast.getMap(LAST_DOCS_MAP);
		String lastDocName = lastViewedMap.executeOnKey(userName, new GetUserLastViewedProcessor(userName));
		if (lastDocName == null) {
			System.out.println("UNEXPECTED: Failed to retrieve name of the last viewed document.");
			return;
		}

		// Try adding it
		IMap<String, List<Comment>> commMap = hazelcast.getMap(DOC_COMMS_MAP);
		boolean success = commMap.executeOnKey(lastDocName, new AddCommentProcessor(lastDocName, comment));
		if (!success) {
			System.out.println("UNEXPECTED: Failed to add a comment to the document.");
			return;
		}

		System.out.printf("Added a comment about %s.%n", lastDocName);
	}

	/*
	 * Main interactive user loop
	 */
	public void run() throws IOException {
		loop:
		while (true) {
			System.out.println("\nAvailable commands (type and press enter):");
			System.out.println(" s - select and show document");
			System.out.println(" i - show document view count and comments");
			System.out.println(" c - add comment");
			System.out.println(" a - add to favorites");
			System.out.println(" r - remove from favorites");
			System.out.println(" n - show next favorite");
			System.out.println(" l - list all favorites");
			System.out.println(" q - quit");
			// read first character
			int c = in.read();
			// throw away rest of the buffered line
			while (in.ready())
				in.read();
			switch (c) {
				case 'q': // Quit the application
					break loop;
				case 's': // Select and show a document
					showCommand();
					break;
				case 'i': // Show view count and comments of the selected document
					infoCommand();
					break;
				case 'c': // Add a comment to the selected document
					commentCommand();
					break;
				case 'a': // Add the selected document to favorites
					addFavoriteCommand();
					break;
				case 'r': // Remove the selected document from favorites
					removeFavoriteCommand();
					break;
				case 'n': // Select and show the next document in the list of favorites
					nextFavoriteCommand();
					break;
				case 'l': // Show the list of favorite documents
					listFavoritesCommand();
					break;
				case '\n':
				default:
					break;
			}
		}
	}

	/*
	 * Main method, creates a client instance and runs its loop
	 */
	public static void main(String[] args) {
		if (args.length < 1 || args.length > 2 || (args.length == 2 && !Objects.equals(args[1], "demo"))) {
			System.err.println("Usage: ./client <userName> [demo]");
			return;
		}

		try {
			Client client = new Client(args[0]);

			if (args.length > 1)
				client.setupDemo();

			client.run();
			try {
			}
			finally {
				client.disconnect();
			}
		}
		catch (Exception e){
			e.printStackTrace();
		}
	}

}
