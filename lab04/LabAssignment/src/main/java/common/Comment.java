package common;

import java.io.Serializable;
import java.util.Date;

public class Comment implements Serializable {

    private String text;
    private String author;
    private Date createdAt;

    public Comment(String text, String author) {
        this.text = text;
        this.author = author;
        this.createdAt = new Date(System.currentTimeMillis());
    }

    @Override
    public String toString() {
        return " --- COMMENT ---\n" +
                "by: " + author + "\n" +
                "at: " + createdAt + "\n" +
                text + "\n" +
                " --- --- --- ---\n";
    }

}
