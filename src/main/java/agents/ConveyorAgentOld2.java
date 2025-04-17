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
import jade.lang.acl.MessageTemplate;
import jade.proto.AchieveREInitiator;
import jade.util.Logger;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

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
public class ConveyorAgentOld2 extends Agent{
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
        private ACLMessage input_msg;
        private int input_msg_type;
        private List<String> target_path;
        private boolean input_msg_unpacked_successfully;

        public HandleRequestMsg(Agent a) {
            super(a);
        }
        public void action() {
            MessageTemplate requestOnly = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);
            input_msg = myAgent.receive(requestOnly);
            if(input_msg != null){
                input_msg_unpacked_successfully = unpackInputMsg();
                if (!input_msg_unpacked_successfully) {
                    return;
                }
                ACLMessage reply = input_msg.createReply();
                if (input_msg_type == TRANSFER_PALLET) {
                    if (state == IDLE) {
                        reply.setPerformative(ACLMessage.AGREE);
                        myAgent.send(reply);
                        wait(100);
                        transfer_pallet();
                    }
                    else if (state == BUSY) {
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
                }

                else if (input_msg_type == FIND_PATH) {
                    System.out.println(name + ": FIND_PATH - not implemented yet");
                }
                else if (input_msg_type == CHANGE_STATE) {
                    System.out.println(name + ": CHANGE_STATE - not implemented yet");
                    
                }
                else {
                    System.out.println(name + ": Unknown input message type " + input_msg_type);
                }
            }
            else {
                block();
            }
        }
        public boolean unpackInputMsg() {
            try {
                JSONParser parser = new JSONParser();
                JSONObject msg_in_json = (JSONObject) parser.parse(input_msg.getContent());
                input_msg_type = ((Number) msg_in_json.get("msg_type")).intValue();
                if (input_msg_type == TRANSFER_PALLET){
                    // Expected message format: {"msg_type": <int>, "target_path": List<String>}
                    target_path = (List<String>) msg_in_json.get("target_path");
                }
                return true;
            }
            catch (ParseException e) {
                System.out.println(name + ": The input message does not follow JSON format");
                return false;
            }
            catch (NullPointerException e) {
                System.out.println(name + ": The input message lacks expected keys or has typos in them");
                return false;
            }

        }
        public void transfer_pallet() {
            // TODO: Handling the "abnormal situations" mentioned in task 3 (at least one of the conveyors in the path is down or busy exceeding waiting timeout)

            String next_cnv_name = null;
            AID next_cnv_aid = null;
            boolean belongs_to_target_path = false;
            boolean destination_reached = false;

            if (!target_path.get(0).equals(name)){
                System.out.println(name + ": Invalid target path " + target_path + ". The conveyor receiving the request " +
                        "message of a type " + input_msg_type + " should be the first conveyor in the target path.");
                return;
            }
            if (target_path.size() == 1){
                System.out.println(name + ": The destination reached");
                return;
            }

            next_cnv_name = target_path.get(1);
            next_cnv_aid = new AID(next_cnv_name, AID.ISLOCALNAME);
            if (!following_conveyors.contains(next_cnv_name)) {
                System.out.println(name + ": Invalid target path " + target_path + " - " + next_cnv_name + " cannot be reached from " + name);
                return;
            }

            load();
            start(next_cnv_aid);

        }
        public void load() {
            System.out.println("\n" + name + ": Loaded");
            state = BUSY;
        }
        public void start(AID next_cnv) {
            System.out.println(name + ": Running (with transfer time of " + transfer_time + " seconds)...");
            long timeout_ms = (long) transfer_time*1000;
            myAgent.addBehaviour(new WakerBehaviour(myAgent, timeout_ms) {
                protected void onWake() {
                    System.out.println(name + ": Finished");
                    unload(next_cnv);
                }
            });
        }
        public void unload(AID next_cnv) {
            ACLMessage load_next_cnv_req = new ACLMessage(ACLMessage.REQUEST);

            JSONObject req_content_json = new JSONObject();
            req_content_json.put("msg_type", TRANSFER_PALLET);
            List<String> new_target_path = new ArrayList<>(target_path.subList(1, target_path.size()));
            req_content_json.put("target_path", new_target_path);
            load_next_cnv_req.setContent(req_content_json.toJSONString());
            load_next_cnv_req.addReceiver(next_cnv);
            load_next_cnv_req.setContent(req_content_json.toJSONString());
            load_next_cnv_req.setConversationId("load-cnv-" + System.currentTimeMillis());
            System.out.println(name + ": Unloading into " + next_cnv.getLocalName() + "...");
            myAgent.addBehaviour(new AchieveREInitiator(myAgent, load_next_cnv_req) {

                protected void handleAgree(ACLMessage agree) {
                    state = IDLE;
                    System.out.println(name + ": Unloaded successfully");
                }

                protected void handleInform(ACLMessage inform) {
                    System.out.println("Received INFORM from " + inform.getSender().getLocalName());
                }

                protected void handleRefuse(ACLMessage refuse) {
                    System.out.println("Request was refused by " + refuse.getSender().getLocalName());
                }

                protected void handleFailure(ACLMessage failure) {
                    System.out.println("Request failed: " + failure.getContent());
                }

                protected void handleAllResultNotifications(Vector notifications) {
                    // Called after all expected responses received or timeout
                }
            });
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

}