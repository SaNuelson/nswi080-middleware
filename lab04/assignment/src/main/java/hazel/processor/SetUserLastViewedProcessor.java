package hazel.processor;

import com.hazelcast.map.EntryProcessor;

import java.util.Map;
import java.util.Objects;

public class SetUserLastViewedProcessor implements EntryProcessor<String, String, Boolean> {

    private String userName;
    private String newDocName;

    public SetUserLastViewedProcessor(String userName, String newDocName) {
        this.userName = userName;
        this.newDocName = newDocName;
    }

    @Override
    public Boolean process(Map.Entry<String, String> entry) {
        if (!Objects.equals(userName, entry.getKey()))
            return false;

        entry.setValue(newDocName);
        return true;
    }

    @Override
    public EntryProcessor<String, String, Boolean> getBackupProcessor() {
        return SetUserLastViewedProcessor.this;
    }
}
