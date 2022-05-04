package hazel.processor;

import com.hazelcast.map.EntryProcessor;
import common.Document;

import java.util.Map;
import java.util.Objects;

public class GetViewsProcessor implements EntryProcessor<String, Integer, Integer> {

    private final String docName;

    public GetViewsProcessor(String docName) {
        this.docName = docName;
    }

    @Override
    public Integer process(Map.Entry<String, Integer> entry) {
        if (!Objects.equals(docName, entry.getKey()))
            return -1;

        int count;
        if (entry.getValue() == null)
            count = 0;
        else
            count = entry.getValue();

        return count;
    }

    @Override
    public EntryProcessor<String, Integer, Integer> getBackupProcessor() {
        return GetViewsProcessor.this;
    }
}
