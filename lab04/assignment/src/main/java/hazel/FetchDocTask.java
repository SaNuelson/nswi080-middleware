package hazel;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastInstanceAware;
import com.hazelcast.map.IMap;
import common.*;
import hazel.processor.IncrementViewsProcessor;
import hazel.processor.SetUserLastViewedProcessor;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.Callable;

import static common.Constants.*;

/**
 * Fetch document for the client and adjust all appropriate related variables. Those include:
 * - creating doc if it doesn't exist yet
 * - incrementing view counter for document
 * - changing last viewed document for user
 */
public class FetchDocTask implements Callable<Document>, Serializable, HazelcastInstanceAware {

    private final String docName;
    private final String clientName;

    public FetchDocTask(String clientName, String docName) {
        this.docName = docName;
        this.clientName = clientName;
    }

    private transient HazelcastInstance hazelcastInstance;

    public void setHazelcastInstance( HazelcastInstance hazelcastInstance ) {
        this.hazelcastInstance = hazelcastInstance;
    }

    public Document call() throws Exception {
        System.out.printf("%s starting...%n", this);

        // execute atomic fetch/caching (and update of metadata) of requested document
        IMap<String, Document> docMap = hazelcastInstance.getMap(DOC_CACHE_MAP);
        Document document = docMap.get(docName);
        if (document == null) {
            tryCreateDoc();
            document = docMap.get(docName);
        }

        // increment view counter
        IMap<String, Integer> viewMap = hazelcastInstance.getMap(DOC_VIEWS_MAP);
        boolean viewChangeSuccess = viewMap.executeOnKey(docName, new IncrementViewsProcessor(docName));
        if (!viewChangeSuccess) {
            System.out.printf("UNEXPECTED: %s failed to increment %s views!", this, docName);
        }

        // change user's last viewed doc
        IMap<String, String> lastDocMap = hazelcastInstance.getMap(LAST_DOCS_MAP);
        boolean lastChangeSuccess = lastDocMap.executeOnKey(clientName, new SetUserLastViewedProcessor(clientName, docName));
        if (!lastChangeSuccess) {
            System.out.printf("UNEXPECTED: %s failed to change %s last viewed doc!", this, clientName);
        }

        return document;
    }

    /**
     * Atomically create the document if it doesn't exist.
     * Avoids using processor due to choking partition threads.
     */
    private void tryCreateDoc() {
        IMap<String, Document> docMap = hazelcastInstance.getMap(DOC_CACHE_MAP);
        docMap.lock(docName);
        if (docMap.get(docName) == null) {
            System.out.printf("%s generating document %s...%n", this, docName);
            Document newDoc = DocumentGenerator.generateDocument(docName);
            docMap.put(docName, newDoc);
            System.out.printf("%s generated document %s%n", this, docName);
        }
        docMap.unlock(docName);
    }

    @Override
    public String toString() {
        return String.format("FetchDocTask(client %s, doc %s)", clientName, docName);
    }
}