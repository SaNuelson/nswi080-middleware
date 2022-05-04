package hazel.processor;

import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.IMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class GetAllFavesProcessor implements EntryProcessor<String, List<String>, List<String>> {

    private String userName;

    public GetAllFavesProcessor(String userName) {
        this.userName = userName;
    }

    @Override
    public List<String> process(Map.Entry<String, List<String>> entry) {
        if (!Objects.equals(userName, entry.getKey()))
            return null;

        List<String> favourites;
        if (entry.getValue() == null)
            favourites = new ArrayList<>();
        else
            favourites = entry.getValue();

        return favourites;
    }

    @Override
    public EntryProcessor<String, List<String>, List<String>> getBackupProcessor() {
        return GetAllFavesProcessor.this;
    }
}
