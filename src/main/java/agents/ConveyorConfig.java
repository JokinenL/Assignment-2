package agents;

import java.io.Serializable;
import java.util.List;

public class ConveyorConfig implements Serializable {
    public List<String> followingConveyors;
    public int transferTime;

    public ConveyorConfig(List<String> followingConveyors, int transferTime) {
        this.followingConveyors = followingConveyors;
        this.transferTime = transferTime;
    }
}
