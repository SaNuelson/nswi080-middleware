package hazel.processor;

import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.IMap;
import common.Comment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class GetAllCommentsProcessor implements EntryProcessor<String, List<Comment>, List<Comment>> {

    private String docName;

    public GetAllCommentsProcessor(String docName) {
        this.docName = docName;
    }

    @Override
    public List<Comment> process(Map.Entry<String, List<Comment>> entry) {
        if (!Objects.equals(docName, entry.getKey()))
            return null;

        List<Comment> comments;
        if (entry.getValue() == null)
            comments = new ArrayList<>();
        else
            comments = entry.getValue();

        return comments;
    }

    @Override
    public EntryProcessor<String, List<Comment>, List<Comment>> getBackupProcessor() {
        return GetAllCommentsProcessor.this;
    }
}
