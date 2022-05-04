package hazel.processor;

import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.IMap;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tries to return the name of next document in list of favorites.
 * Return null if:
 * - list is not instantiated or empty (both meaning there's no favorites)
 * - provided last document is not a favorite (user can fix that by explicitly listing favourites and showing one)
 * Otherwise returns name of the next document in list of favourites.
 */
public class GetNextFaveProcessor implements EntryProcessor<String, List<String>, String> {

    private String userName;
    private String docName;

    public GetNextFaveProcessor(String userName, String docName) {
        this.userName = userName;
        this.docName = docName;
    }

    @Override
    public String process(Map.Entry<String, List<String>> entry) {
        if (!Objects.equals(userName, entry.getKey()))
            return null;

        if (entry.getValue() == null || entry.getValue().size() == 0)
            return null;

        List<String> favourites = entry.getValue();
        int lastIndex = favourites.indexOf(docName);

        if (lastIndex == -1)
            return null;

        int newIndex = (lastIndex + 1) % favourites.size();

        return favourites.get(newIndex);
    }

    @Override
    public EntryProcessor<String, List<String>, String> getBackupProcessor() {
        return GetNextFaveProcessor.this;
    }
}
