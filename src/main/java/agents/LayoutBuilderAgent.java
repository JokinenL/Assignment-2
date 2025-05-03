package agents;

import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.util.Logger;
import jade.wrapper.AgentController;
import jade.wrapper.ContainerController;
import jade.wrapper.StaleProxyException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LayoutBuilderAgent extends Agent {
    private Logger myLogger = Logger.getMyLogger(getClass().getName());



    protected void setup() {
        // Registration with the DF
        ContainerController cc = getContainerController();

        try {
            ConveyorConfig config1 = new ConveyorConfig(Collections.singletonList("CNV2"), 3500);
            Object[] args1 = new Object[] { config1 };
            AgentController ac1 = cc.createNewAgent("CNV1", "agents.ConveyorAgent", args1);
            ac1.start();

            ConveyorConfig config2 = new ConveyorConfig(Collections.singletonList("CNV3"), 8000);
            Object[] args2 = new Object[] { config2 };
            AgentController ac2 = cc.createNewAgent("CNV2", "agents.ConveyorAgent", args2);
            ac2.start();

            ConveyorConfig config3 = new ConveyorConfig(Arrays.asList("CNV4", "CNV13"), 2000);
            Object[] args3 = new Object[] { config3 };
            AgentController ac3 = cc.createNewAgent("CNV3", "agents.ConveyorAgent", args3);
            ac3.start();

            ConveyorConfig config4 = new ConveyorConfig(Collections.singletonList("CNV5"), 5000);
            Object[] args4 = new Object[] { config4 };
            AgentController ac4 = cc.createNewAgent("CNV4", "agents.ConveyorAgent", args4);
            ac4.start();

            ConveyorConfig config5 = new ConveyorConfig(Collections.singletonList("CNV6"), 4000);
            Object[] args5 = new Object[] { config5 };
            AgentController ac5 = cc.createNewAgent("CNV5", "agents.ConveyorAgent", args5);
            ac5.start();

            ConveyorConfig config6 = new ConveyorConfig(Collections.singletonList("CNV7"), 4000);
            Object[] args6 = new Object[] { config6 };
            AgentController ac6 = cc.createNewAgent("CNV6", "agents.ConveyorAgent", args6);
            ac6.start();

            ConveyorConfig config7 = new ConveyorConfig(Collections.singletonList("CNV8"), 6000);
            Object[] args7 = new Object[] { config7 };
            AgentController ac7 = cc.createNewAgent("CNV7", "agents.ConveyorAgent", args7);
            ac7.start();

            ConveyorConfig config8 = new ConveyorConfig(Arrays.asList("CNV9", "CNV14"), 2000);
            Object[] args8 = new Object[] { config8 };
            AgentController ac8 = cc.createNewAgent("CNV8", "agents.ConveyorAgent", args8);
            ac8.start();

            ConveyorConfig config9 = new ConveyorConfig(Collections.singletonList("CNV10"), 2000);
            Object[] args9 = new Object[] { config9 };
            AgentController ac9 = cc.createNewAgent("CNV9", "agents.ConveyorAgent", args9);
            ac9.start();

            ConveyorConfig config10 = new ConveyorConfig(Collections.singletonList("CNV11"), 3000);
            Object[] args10 = new Object[] { config10 };
            AgentController ac10 = cc.createNewAgent("CNV10", "agents.ConveyorAgent", args10);
            ac10.start();

            ConveyorConfig config11 = new ConveyorConfig(Collections.singletonList("CNV12"), 4000);
            Object[] args11 = new Object[] { config11 };
            AgentController ac11 = cc.createNewAgent("CNV11", "agents.ConveyorAgent", args11);
            ac11.start();

            ConveyorConfig config12 = new ConveyorConfig(Collections.singletonList("CNV1"), 3000);
            Object[] args12 = new Object[] { config12 };
            AgentController ac12 = cc.createNewAgent("CNV12", "agents.ConveyorAgent", args12);
            ac12.start();

            ConveyorConfig config13 = new ConveyorConfig(Arrays.asList("CNV9", "CNV14"), 5000);
            Object[] args13 = new Object[] { config13 };
            AgentController ac13 = cc.createNewAgent("CNV13", "agents.ConveyorAgent", args13);
            ac13.start();

            ConveyorConfig config14 = new ConveyorConfig(Collections.singletonList("CNV12"), 5000);
            Object[] args14 = new Object[] { config14 };
            AgentController ac14 = cc.createNewAgent("CNV14", "agents.ConveyorAgent", args14);
            ac14.start();


        } catch (StaleProxyException e) {
            e.printStackTrace();
        }
    }
}