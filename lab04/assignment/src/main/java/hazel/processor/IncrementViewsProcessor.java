package hazel.processor;

import com.hazelcast.map.EntryProcessor;
import common.Document;

import java.util.Map;
import java.util.Objects;

public class IncrementViewsProcessor implements EntryProcessor<String, Integer, Boolean> {

    private final String docName;

    public IncrementViewsProcessor(String docName) {
        this.docName = docName;
    }

    @Override
    public Boolean process(Map.Entry<String, Integer> entry) {
        if (!Objects.equals(docName, entry.getKey()))
            return false;

        int oldCount;
        if (entry.getValue() == null)
            oldCount = 0;
        else
            oldCount = entry.getValue();

        entry.setValue(oldCount + 1);

        return true;
    }

    @Override
    public EntryProcessor<String, Integer, Boolean> getBackupProcessor() {
        return IncrementViewsProcessor.this;
    }
}
