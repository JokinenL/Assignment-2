/*****************************************************************
 JADE - Java Agent DEvelopment Framework is a framework to develop
 multi-agent systems in compliance with the FIPA specifications.
 Copyright (C) 2000 CSELT S.p.A.

 GNU Lesser General Public License

 This library is free software; you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public
 License as published by the Free Software Foundation,
 version 2.1 of the License.


 This library is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 Lesser General Public License for more details.

 You should have received a copy of the GNU Lesser General Public
 License along with this library; if not, write to the
 Free Software Foundation, Inc., 59 Temple Place - Suite 330,
 Boston, MA  02111-1307, USA.
 *****************************************************************/

package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.util.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.util.List;

/**
 * This agent implements a simple Ping Agent that registers itself with the DF and
 * then waits for ACLMessages.
 * If  a REQUEST message is received containing the string "ping" within the content
 * then it replies with an INFORM message whose content will be the string "pong".
 *
 * @author Tiziana Trucco - CSELT S.p.A.
 * @version  $Date: 2010-04-08 13:08:55 +0200 (gio, 08 apr 2010) $ $Revision: 6297 $
 */
public class ConveyorAgentOld extends Agent{
    // States
    private final int IDLE = 0;
    private final int BUSY = 1;
    private final int DOWN = 2;

    // Message sending directions
    //     FORWARD => send to following conveyor(s)
    //     BACKWARD => send to previous conveyor in the midpoint list
    private final int FORWARD = 1;
    private final int BACKWARD = -1;

    /*
    Message types
        0: TRANSFER_PALLET => Transferring a pallet across predefined path
            Example: {"msg_type": 0, "target_path": ["CNV10", "CNV11", "CNV12", "CNV13"]}

        1: FIND_PATH => Finding a path from "src" to "dest"
            Example: {"msg_type": 1, "src": "CNV1", "dest": "CNV4", "midpoints": ["CNV2", "CNV3"], "dest_reached": true, "msg_dir": 1}

        2: CHANGE_STATE => Changing the state of the conveyor receiving the message
            Example: {"msg_type": 2, "new_state": 2}
     */
    private final int TRANSFER_PALLET = 0;
    private final int FIND_PATH = 1;
    private final int CHANGE_STATE = 2;

    // Rate on which the request is sent again to the next conveyor on the path if it was busy earlier.
    private final int REQUEST_RATE = 3;

    private Logger logger = Logger.getMyLogger(getClass().getName());
    private List<String> following_conveyors;
    private Object [] args;
    private int transfer_time;
    private ConveyorConfig config;
    private int state = IDLE;
    private String name;

    private class HandleRequestMsg extends CyclicBehaviour {
        private final JSONParser parser = new JSONParser();

        public HandleRequestMsg(Agent a) {
            super(a);
        }
        public void action() {
            ACLMessage msg_in = myAgent.receive();
            if(msg_in != null){
                ACLMessage reply = msg_in.createReply();
                if(msg_in.getPerformative()== ACLMessage.REQUEST){
                    String content = msg_in.getContent();
                    if ((content != null)){
                        try {
                            JSONObject msg_in_json = (JSONObject) parser.parse(content);
                            int msg_type = ((Number) msg_in_json.get("msg_type")).intValue();
                            if (msg_type == TRANSFER_PALLET){
                                if (state == BUSY) {
                                    reply.setPerformative(ACLMessage.REFUSE);
                                    JSONObject reply_content_json = new JSONObject();
                                    reply_content_json.put("state", state);
                                    reply.setContent(reply_content_json.toJSONString());
                                    myAgent.send(reply);
                                    System.out.println(name + ": Refusing request - state == BUSY");

                                }
                                else if (state == DOWN){
                                    reply.setPerformative(ACLMessage.REFUSE);
                                    JSONObject reply_content_json = new JSONObject();
                                    reply_content_json.put("state", state);
                                    reply.setContent(reply_content_json.toJSONString());
                                    myAgent.send(reply);
                                    System.out.println(name + ": Refusing request - state == DOWN");
                                }
                                else { // if (state == IDLE)
                                    reply.setPerformative(ACLMessage.AGREE);
                                    myAgent.send(reply);
                                    // transfer_pallet(msg_in_json);
                                    System.out.println(name + ": Running...");
                                    state = BUSY;
                                    long timeout_ms = (long) transfer_time*1000;
                                    myAgent.addBehaviour(new WakerBehaviour(myAgent, timeout_ms) {
                                        protected void onWake() {
                                            System.out.println(name + ": Finished running conveyor.");
                                            state = IDLE;
                                        }
                                    });
                                }
                            }
                            else if (msg_type == FIND_PATH){
                                find_path();
                            }
                            else if (msg_type == CHANGE_STATE){
                                change_state(msg_in_json);
                            }
                        }
                        catch (ParseException e) {
                            System.out.println(name + ": Received a message of unexpected format");
                            e.printStackTrace();
                        }
                        catch (NullPointerException e) {
                            System.out.println(name + ": NullPointerException occurred during handling of the request message. This refers to missing keys in the message.");
                            e.printStackTrace();
                        }
                    }
                    else{
                        logger.log(Logger.INFO, "Agent " + name + " - Unexpected request with null content received from " + msg_in.getSender().getLocalName());
                    }
                }
                else {
                    logger.log(Logger.INFO, "Agent " + name + " - Unexpected message [" + ACLMessage.getPerformative(msg_in.getPerformative()) + "] received from " + msg_in.getSender().getLocalName());
                }
            }
            else {
                block();
            }
        }

        public void transfer_pallet(JSONObject msg_in_json) {
            // TODO: Handling the "abnormal situations" mentioned in task 3 (at least one of the conveyors in the path is down or busy exceeding waiting timeout)
            String next_cnv_name = null;
            AID next_cnv_aid = null;
            JSONArray target_path_json = (JSONArray) msg_in_json.get("target_path");
            boolean belongs_to_target_path = false;
            boolean destination_reached = false;
            for (int i = 0; i < target_path_json.size(); i++) {
                String cnv_i = target_path_json.get(i).toString();
                if (cnv_i.equals(name)){
                    belongs_to_target_path = true;
                    if (i < target_path_json.size() - 1){
                        next_cnv_name = target_path_json.get(i+1).toString();
                        next_cnv_aid = new AID(next_cnv_name, AID.ISLOCALNAME);
                    }
                    else{
                        destination_reached = true;
                    }
                    break;
                }
            }
            if (!belongs_to_target_path){
                System.out.println(name + ": This conveyor is not included in the target path!");
            }
            else {
                loadCnv();
                if (!destination_reached){
                    runCnv();
                    System.out.println(name + ": Unloading the pallet into " + next_cnv_name);
                    unloadCnv();
                    ACLMessage msg_out = new ACLMessage(ACLMessage.REQUEST);
                    msg_out.addReceiver(next_cnv_aid);
                    msg_out.setContent(msg_in_json.toJSONString());
                    myAgent.send(msg_out);
                }
                else {
                    System.out.println(name + ": The destination reached\n");
                }
            }

        }
        public void find_path() {
            // TODO: Implementation of the whole path finding logic for the task 2

        }
        public void change_state(JSONObject msg_in_json) {

            int new_state = ((Number) msg_in_json.get("new_state")).intValue();
            if (new_state == IDLE){
                resetCnv();
            }
            else if (new_state == BUSY){
                loadCnv();
            }
            else if (new_state == DOWN){
                takeDownCnv();
            }
        }

    }
    
    protected void setup() {
        args = getArguments();
        config = (ConveyorConfig) args[0];
        following_conveyors = config.followingConveyors;
        transfer_time = config.transferTime;
        name = getLocalName();
        logger.log(Logger.INFO, getLocalName() + " started" +
                "\n    following conveyors: " + following_conveyors +
                "\n    transfer time: " + transfer_time + "\n");

        DFAgentDescription dfd = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("ConveyorAgent");
        sd.setName(getName());
        sd.setOwnership("TILAB");
        dfd.setName(getAID());
        dfd.addServices(sd);
        try {
            DFService.register(this,dfd);
            HandleRequestMsg wait_msg_behavior = new HandleRequestMsg(this);
            addBehaviour(wait_msg_behavior);
        } catch (FIPAException e) {
            logger.log(Logger.SEVERE, "Agent " + name + " - Cannot register with DF", e);
            doDelete();
        }
    }

    public void wait(int ms)
    {
        try
        {
            Thread.sleep(ms);
        }
        catch(InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }
    }

    public void runCnv() {
        System.out.println(name + ": Running (with transfer time of " + transfer_time + " seconds)...");
        wait(transfer_time*1000);
        System.out.println(name + ": Finished");
    }

    public void loadCnv() {
        System.out.println(name + ": Loaded");
        state = BUSY;
    }

    public void unloadCnv() {
        System.out.println(name + ": Unloaded\n");
        state = IDLE;
    }
    public void takeDownCnv() {
        System.out.println(name + ": Taking down");
        state = DOWN;
    }
    public void resetCnv() {
        System.out.println(name + ": Resetting");
        state = IDLE;
    }
}