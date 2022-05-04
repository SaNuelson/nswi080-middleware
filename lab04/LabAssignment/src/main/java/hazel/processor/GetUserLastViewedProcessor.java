package hazel.processor;

import com.hazelcast.map.EntryProcessor;

import java.util.Map;
import java.util.Objects;

public class GetUserLastViewedProcessor implements EntryProcessor<String, String, String> {

    private String userName;

    public GetUserLastViewedProcessor(String userName) {
        this.userName = userName;
    }

    @Override
    public String process(Map.Entry<String, String> entry) {
        if (!Objects.equals(userName, entry.getKey()))
            return null;

        return entry.getValue();
    }

    @Override
    public EntryProcessor<String, String, String> getBackupProcessor() {
        return GetUserLastViewedProcessor.this;
    }
}
