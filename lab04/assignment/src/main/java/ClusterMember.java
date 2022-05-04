import com.hazelcast.config.Config;
import com.hazelcast.config.FileSystemYamlConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import common.Constants;
import common.Document;

import java.io.FileNotFoundException;
import java.io.IOException;

public class ClusterMember {

    // Hazel instance where member is running
    HazelcastInstance hazelcast;

    public ClusterMember(String prefix) throws FileNotFoundException {

        Config config = new FileSystemYamlConfig("hazelcast.yaml");

        hazelcast = Hazelcast.newHazelcastInstance(config);
        String memberName = hazelcast.getName();

        System.out.printf("ClusterMember %s:%s constructed.%n", prefix, memberName);
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: bash run-member.sh <prefix>");
            return;
        }
        String prefix = args[0];

        try {
            ClusterMember member = new ClusterMember(prefix);

            try {
                System.out.println("Press enter to exit");
                System.in.read();
            } catch (IOException e) {
                e.printStackTrace();
            }

            member.hazelcast.shutdown();

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }


}
