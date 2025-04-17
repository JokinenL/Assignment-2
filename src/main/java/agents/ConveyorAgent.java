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
import jade.core.behaviours.TickerBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.proto.AchieveREInitiator;
import jade.util.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import java.lang.NullPointerException;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

/**
 * This agent implements a simple Ping Agent that registers itself with the DF and
 * then waits for ACLMessages.
 * If  a REQUEST message is received containing the string "ping" within the content
 * then it replies with an INFORM message whose content will be the string "pong".
 *
 * @author Tiziana Trucco - CSELT S.p.A.
 * @version  $Date: 2010-04-08 13:08:55 +0200 (gio, 08 apr 2010) $ $Revision: 6297 $
 */
public class ConveyorAgent extends Agent{
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

        Type 1 (FIND_PATH) is not used yet in the code since it belongs to task 2 not task1. However, I thought that the format presented
        above would be suitable for the message that is being sent between the conveyor agents when trying to find the optimal path.
        Feel free to modify the format of FIND_PATH message if you have another idea on how it should be implemented.
     */
    private final int TRANSFER_PALLET = 0;
    private final int FIND_PATH = 1;
    private final int CHANGE_STATE = 2;

    // Rate (ms) on which the request is sent again to the next conveyor on the path if it was busy earlier.
    private final int LOAD_REQUEST_FREQUENCY = 5000; //

    private Logger logger = Logger.getMyLogger(getClass().getName());
    private JSONParser parser = new JSONParser();
    private List<String> following_conveyors;
    private Object [] args;
    private int transfer_time; // ms
    private ConveyorConfig config;
    private int state = IDLE;
    private String name;

    public class HandleRequestMsg extends CyclicBehaviour {

        public HandleRequestMsg(Agent a) {
            super(a);
        }
        public void action() {
            MessageTemplate requestOnly = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);
            ACLMessage req_msg = myAgent.receive(requestOnly);
            if(req_msg != null){
                try {
                    ACLMessage reply = req_msg.createReply();
                    JSONObject req_msg_json = (JSONObject) parser.parse(req_msg.getContent());
                    int input_msg_type = ((Number) req_msg_json.get("msg_type")).intValue();
                    if (input_msg_type == TRANSFER_PALLET){
                        // Expected message format: {"msg_type": <int>, "target_path": List<String>}
                        List<String> target_path = (List<String>) req_msg_json.get("target_path");
                        if (state == IDLE) {
                            if (!target_path.get(0).equals(name)){
                                System.out.println(name + ": Invalid target path " + target_path + ". The conveyor receiving the request " +
                                        "message of a type " + getStateName(TRANSFER_PALLET) + "\nshould be the first conveyor in the target path.");
                                reply.setPerformative(ACLMessage.REFUSE);
                                JSONObject reply_content_json = new JSONObject();
                                reply_content_json.put("state", state);
                                reply.setContent(reply_content_json.toJSONString());
                                myAgent.send(reply);
                                return;
                            }
                            if (target_path.size() > 1 && !following_conveyors.contains(target_path.get(1))) {
                                System.out.println(name + ": Invalid target path " + target_path + " - " + target_path.get(1) + " cannot be reached from " + name);
                                reply.setPerformative(ACLMessage.REFUSE);
                                JSONObject reply_content_json = new JSONObject();
                                reply_content_json.put("state", state);
                                reply.setContent(reply_content_json.toJSONString());
                                myAgent.send(reply);
                                return;
                            }
                            reply.setPerformative(ACLMessage.AGREE);
                            myAgent.send(reply);
                            wait(100);
                            loadConveyor();
                            if (target_path.size() == 1){
                                System.out.println(name + ": The destination reached\n");
                                return;
                            }
                            String next_cnv_name = target_path.get(1);
                            AID next_cnv_aid = new AID(next_cnv_name, AID.ISLOCALNAME);
                            startConveyor(next_cnv_aid, target_path);
                        }
                        else if (state == BUSY) {
                            reply.setPerformative(ACLMessage.REFUSE);
                            JSONObject reply_content_json = new JSONObject();
                            reply_content_json.put("state", state);
                            reply.setContent(reply_content_json.toJSONString());
                            myAgent.send(reply);
                            // System.out.println("--------------------------------------");
                            System.out.println("*** " + name + ": Refusing request from" + req_msg.getSender().getLocalName() + " - state == BUSY ***");
                            // System.out.println("--------------------------------------");

                        }
                        else if (state == DOWN){
                            reply.setPerformative(ACLMessage.REFUSE);
                            JSONObject reply_content_json = new JSONObject();
                            reply_content_json.put("state", state);
                            reply.setContent(reply_content_json.toJSONString());
                            myAgent.send(reply);
                            System.out.println("*** " + name + ": Refusing request from" + req_msg.getSender().getLocalName() + " - state == DOWN ***");
                        }
                    }
                    else if (input_msg_type == FIND_PATH) {
                        System.out.println(name + ": FIND_PATH - not implemented yet");
                    }
                    else if (input_msg_type == CHANGE_STATE) {
                        int new_state = ((Number) req_msg_json.get("new_state")).intValue();
                        state = new_state;
                        System.out.println("*** " + name + ": State changed to " + getStateName(state) + " ***");
                    }
                    else {
                        System.out.println(name + ": Unknown input message type " + input_msg_type);
                    }
                }
                catch (ParseException e) {
                    System.out.println(name + ": The input message does not follow JSON format");
                }
                catch (NullPointerException e) {
                    System.out.println(name + ": The input message lacks expected keys or has typos in them");
                }
            }
            else {
                block();
            }
        }
        public String getStateName(int state_id) {
            if (state == IDLE) {
                return "IDLE";
            }
            else if (state == BUSY) {
                return "BUSY";
            }
            else if (state == DOWN) {
                return "DOWN";
            }
            else { // This should never happen
                return "UNKNOWN";
            }
        }

        public void loadConveyor() {
            System.out.println("\n" + name + ": Loaded");
            state = BUSY;
        }
        public void startConveyor(AID next_cnv, List<String> target_path) {
            System.out.println(name + ": Running (with transfer time of " + (transfer_time/1000.0) + " seconds)...");
            long timeout_ms = (long) transfer_time;
            myAgent.addBehaviour(new WakerBehaviour(myAgent, timeout_ms) {
                protected void onWake() {
                    System.out.println(name + ": Finished");
                    unloadConveyor(next_cnv, target_path);
                }
            });
        }
        public void unloadConveyor(AID next_cnv, List<String> target_path) {
            JSONObject req_content_json = new JSONObject();
            req_content_json.put("msg_type", TRANSFER_PALLET);
            List<String> new_target_path = new ArrayList<>(target_path.subList(1, target_path.size()));
            req_content_json.put("target_path", new_target_path);
            System.out.println(name + ": Unloading into " + next_cnv.getLocalName() + "...");

            RepetitiveLoadRequestBehaviour load_request_behaviour = new RepetitiveLoadRequestBehaviour(myAgent, LOAD_REQUEST_FREQUENCY, req_content_json, next_cnv);
            myAgent.addBehaviour(load_request_behaviour);
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
    }
    public class RepetitiveLoadRequestBehaviour extends TickerBehaviour {

        private boolean agreeReceived = false;
        JSONObject request_content;
        AID receiver;
        String conversation_id;

        public RepetitiveLoadRequestBehaviour(Agent a, long interval, JSONObject request_content, AID receiver) {
            super(a, interval);
            this.request_content = request_content;
            this.receiver = receiver;
            this.conversation_id = "load-request-sequence-" + name + "-" + receiver.getLocalName() + "-" + System.currentTimeMillis();
        }

        @Override
        protected void onTick() {
            if (agreeReceived) {
                stop(); // Stop retrying once we get AGREE
                return;
            }

            // Create a new request message each time
            ACLMessage load_request = new ACLMessage(ACLMessage.REQUEST);
            load_request.setContent(request_content.toJSONString());
            load_request.addReceiver(receiver);
            load_request.setContent(request_content.toJSONString());
            load_request.setConversationId(conversation_id);
            load_request.setReplyWith("req" + System.currentTimeMillis());
            myAgent.addBehaviour(new AchieveREInitiator(myAgent, load_request) {

                @Override
                protected void handleAgree(ACLMessage agree) {
                    agreeReceived = true;
                    state = IDLE;
                    System.out.println(name + ": Unloaded successfully\n");
                }
                @Override
                protected void handleInform(ACLMessage inform) {
                    System.out.println(name + ": Received INFORM from " + inform.getSender().getLocalName());
                }
                @Override
                protected void handleRefuse(ACLMessage refuse) {
                    System.out.println(name + ": Load request was refused by " + refuse.getSender().getLocalName());
                }
                @Override
                protected void handleFailure(ACLMessage failure) {
                    System.out.println(name + ": Load request failed: " + failure.getContent());
                }
                @Override
                protected void handleAllResultNotifications(Vector notifications) {
                    // Called after all expected responses received or timeout
                }
            });
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
                "\n    transfer time: " + transfer_time + " ms\n");

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

}