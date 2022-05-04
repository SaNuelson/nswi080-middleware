package hazel.processor;

import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.IMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class RemoveFromFavesProcessor implements EntryProcessor<String, List<String>, Boolean> {

    private String userName;
    private String docName;

    public RemoveFromFavesProcessor(String userName, String docName) {
        this.userName = userName;
        this.docName = docName;
    }

    @Override
    public Boolean process(Map.Entry<String, List<String>> entry) {
        if (!Objects.equals(userName, entry.getKey()))
            return null;

        if (entry.getValue() == null)
            return false;

        List<String> favourites = entry.getValue();

        int index = favourites.indexOf(docName);

        if (index == -1)
            return false;

        favourites.remove(docName);
        entry.setValue(favourites);
        return true;
    }

    @Override
    public EntryProcessor<String, List<String>, Boolean> getBackupProcessor() {
        return RemoveFromFavesProcessor.this;
    }
}
