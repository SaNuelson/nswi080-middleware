package hazel.processor;

import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.IMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class AddToFavesProcessor implements EntryProcessor<String, List<String>, Boolean> {

    private String userName;
    private String docName;

    public AddToFavesProcessor(String userName, String docName) {
        this.userName = userName;
        this.docName = docName;
    }

    @Override
    public Boolean process(Map.Entry<String, List<String>> entry) {
        if (!Objects.equals(userName, entry.getKey()))
            return null;

        List<String> favourites;
        if (entry.getValue() == null)
            favourites = new ArrayList<>();
        else
            favourites = entry.getValue();

        // If it already is favourited, no update happens
        if (favourites.contains(docName))
            return false;

        favourites.add(docName);
        entry.setValue(favourites);
        return true;
    }

    @Override
    public EntryProcessor<String, List<String>, Boolean> getBackupProcessor() {
        return AddToFavesProcessor.this;
    }
}
