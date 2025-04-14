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

import jade.core.Agent;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.util.Logger;

import java.util.ArrayList;
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
public class ConveyorAgent extends Agent{
    private final int IDLE = 0;
    private final int BUSY = 1;
    private final int DOWN = 2;

    private Logger my_logger = Logger.getMyLogger(getClass().getName());
    private List<String> following_conveyors;
    private Object [] args;
    private int transfer_time;
    private ConveyorConfig config;
    private int state = IDLE;


    protected void setup() {
        args = getArguments();
        config = (ConveyorConfig) args[0];
        following_conveyors = config.followingConveyors;
        transfer_time = config.transferTime;
        my_logger.log(Logger.INFO, getLocalName() + " started" +
                "\n    following conveyors: " + following_conveyors +
                "\n    transfer time: " + transfer_time + "\n");
    }
}