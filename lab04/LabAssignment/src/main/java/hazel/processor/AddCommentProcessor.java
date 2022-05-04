package hazel.processor;

import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.IMap;
import common.Comment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class AddCommentProcessor implements EntryProcessor<String, List<Comment>, Boolean> {

    private Comment comment;
    private String docName;

    public AddCommentProcessor(String docName, Comment comment) {
        this.docName = docName;
        this.comment = comment;
    }

    @Override
    public Boolean process(Map.Entry<String, List<Comment>> entry) {
        if (!Objects.equals(docName, entry.getKey()))
            return false;

        List<Comment> comments;
        if (entry.getValue() == null)
            comments = new ArrayList<>();
        else
            comments = entry.getValue();

        comments.add(comment);
        entry.setValue(comments);
        return true;
    }

    @Override
    public EntryProcessor<String, List<Comment>, Boolean> getBackupProcessor() {
        return AddCommentProcessor.this;
    }
}
