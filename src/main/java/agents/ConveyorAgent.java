package agents;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.proto.AchieveREInitiator;
import jade.util.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.util.*;

/** Task-1 pallet transfer + Task-2 distributed shortest-hop routing. */
public class ConveyorAgent extends Agent {

    /* ─── Constants ───────────────────────────────────────────────────── */
    private static final int IDLE = 0, BUSY = 1, DOWN = 2;

    private static final int TRANSFER_PALLET  = 0;
    private static final int FIND_PATH_REQ    = 1;
    private static final int CHANGE_STATE     = 2;
    private static final int FIND_PATH_REPLY  = 3;
    private static final int FIND_PATH_ABORT  = 4;

    private static final long LOAD_RETRY_MS   = 4_000;
    private static final long BUSY_TIMEOUT_MS = 8_000;

    /* ─── Fields ──────────────────────────────────────────────────────── */
    private final Logger log       = Logger.getMyLogger(getClass().getName());
    private final JSONParser parser = new JSONParser();

    private List<String> neighbours;
    private int          transferMs;
    private String       me;

    private int  state = IDLE;
    private long busySince = 0;

    // For each route‐finding query_id, track best cost & predecessor
    private final Map<String, Double> bestCost    = new HashMap<>();
    private final Map<String, AID   > predecessor = new HashMap<>();

    private String currentDestination; // To track current transfer destination
    /* ─── Setup ──────────────────────────────────────────────────────── */
    @Override
    protected void setup() {
        ConveyorConfig cfg = (ConveyorConfig) getArguments()[0];
        neighbours = cfg.followingConveyors;
        transferMs = cfg.transferTime;
        me         = getLocalName();

        log.info(me + " up  nbs=" + neighbours + "  t=" + transferMs + "ms");

        addBehaviour(new MsgPump(this));
        addBehaviour(new BusyTimeout(this, 1_000));

        currentDestination = null; // initialize currentDestination
    }

    /* ─── Message pump ───────────────────────────────────────────────── */
    private class MsgPump extends CyclicBehaviour {
        MsgPump(Agent a) { super(a); }
        public void action() {
            ACLMessage m = myAgent.receive();
            if (m == null) { block(); return; }
            String content = m.getContent();
            if (content == null || content.trim().isEmpty()) { block(); return; }
            try {
                JSONObject js = (JSONObject) parser.parse(content);
                int type = ((Number) js.get("msg_type")).intValue();
                switch (type) {
                    case TRANSFER_PALLET -> handleTransfer(m, js);
                    case FIND_PATH_REQ   -> handleFindReq  (m, js);
                    case FIND_PATH_REPLY -> handleFindReply(m, js);
                    case FIND_PATH_ABORT -> handleAbort    (m, js);
                    case CHANGE_STATE    -> {
                        state = ((Number) js.get("new_state")).intValue();
                        log.info(me + " state→" + stateName(state));
                    }
                }
            } catch (Exception e) {
                log.warning(me + " parse error: " + e.getMessage());
            }
        }
    }

    /* ─── Task 1: Transfer a pallet along a given path ───────────────── */
    @SuppressWarnings("unchecked")
    private void handleTransfer(ACLMessage m, JSONObject js) {
        List<String> path = (List<String>) js.get("target_path");
        long elapsed = js.get("elapsed_ms") == null ? 0
                : ((Number) js.get("elapsed_ms")).longValue();

        if (state != IDLE) { refuse(m, "BUSY/DOWN"); return; }
        if (!path.get(0).equals(me)) { refuse(m, "wrong first hop"); return; }
        if (path.size() > 1 && !neighbours.contains(path.get(1))) {
            refuse(m, "bad next hop"); return;
        }
        agree(m);
        state = BUSY;
        busySince = System.currentTimeMillis();
        currentDestination = path.get(path.size() - 1); // Stores destination
        log.info(me + " LOAD");

        // Destination reached?
        if (path.size() == 1) {
            log.info(String.format("%s DESTINATION  total %.2f s",
                    me, (elapsed + transferMs) / 1000.0));
            state = IDLE;
            return;
        }

        String next = path.get(1);
        AID nxt = new AID(next, AID.ISLOCALNAME);

        addBehaviour(new WakerBehaviour(this, transferMs) {
            protected void onWake() {
                log.info(me + " UNLOAD→" + next);
                state = IDLE;
                currentDestination = null;
                JSONObject nx = new JSONObject();
                nx.put("msg_type", TRANSFER_PALLET);
                nx.put("target_path", path.subList(1, path.size()));
                nx.put("elapsed_ms", elapsed + transferMs);
                addBehaviour(new Loader(ConveyorAgent.this, nxt, nx));
            }
        });
    }

    // Static helper to build the ACLMessage before calling super()
    private static ACLMessage buildRequest(AID rec, JSONObject js) {
        ACLMessage r = new ACLMessage(ACLMessage.REQUEST);
        r.addReceiver(rec);
        r.setContent(js.toJSONString());
        return r;
    }

    /** Retries load‐request until the next conveyor AGREEs. */
    private class Loader extends AchieveREInitiator {
        private List<String> path; // To track path to transfer to

        Loader(Agent a, AID rec, JSONObject js) {
            super(a, buildRequest(rec, js));
            this.path = (List<String>) js.get("target_path"); // stores the path
        }
        protected void handleRefuse(ACLMessage refuse) {
            String reason = refuse.getContent();
            if ("DOWN".equals(reason)) {
                String destination = path.get(path.size() - 1);
                log.info(me + " DOWN - reroute to " + destination);

                String queryID = UUID.randomUUID().toString();
                JSONObject req = new JSONObject();
                req.put("msg_type", FIND_PATH_REQ);
                req.put("query_id", queryId);
                req.put("dest", destination);
                req.put("cost", 0);
                req.put("path", new JSONArray());

                ACLMessage r = new ACLMessage(ACLMessage.REQUEST);
                r.addReceiver(getAID());
                r.setContent(req.toJSONString());
                send(r);
            } else {
                addBehaviour(new WakerBehaviour(myAgent, LOAD_RETRY_MS) {
                    protected void onWake() { reset(); }
                });
            }
        }
    }

    /* ─── Task 2: Distributed BFS for shortest path ──────────────────── */
    @SuppressWarnings("unchecked")
    private void handleFindReq(ACLMessage m, JSONObject js) {
        String queryId = (String) js.get("query_id");
        String dest    = (String) js.get("dest");
        double cost    = ((Number) js.get("cost")).doubleValue();
        JSONArray path = (JSONArray) js.get("path");

        log.info(me + " got FIND_PATH_REQ [" + queryId + "] dest=" + dest + " cost=" + cost);

        if (bestCost.containsKey(queryId) && bestCost.get(queryId) <= cost) return;
        bestCost.put(queryId, cost);
        predecessor.put(queryId, m.getSender());

        if (state != IDLE) return;

        if (me.equals(dest)) {
            JSONObject rep = new JSONObject();
            rep.put("msg_type", FIND_PATH_REPLY);
            rep.put("query_id", queryId);
            rep.put("cost", cost + 1);
            path.add(me);
            rep.put("path", path);

            ACLMessage reply = m.createReply();
            reply.setPerformative(ACLMessage.INFORM);
            reply.setContent(rep.toJSONString());
            send(reply);
            return;
        }

        path.add(me);
        for (String nb : neighbours) {
            if (nb.equals(m.getSender().getLocalName())) continue;
            JSONObject fwd = new JSONObject();
            fwd.put("msg_type", FIND_PATH_REQ);
            fwd.put("query_id", queryId);
            fwd.put("dest", dest);
            fwd.put("cost", cost + 1);
            fwd.put("path", path.clone());

            ACLMessage fmsg = new ACLMessage(ACLMessage.REQUEST);
            fmsg.addReceiver(new AID(nb, AID.ISLOCALNAME));
            fmsg.setContent(fwd.toJSONString());
            send(fmsg);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleFindReply(ACLMessage m, JSONObject js) {
        String queryId = (String) js.get("query_id");
        JSONArray path = (JSONArray) js.get("path");

        log.info(me + " got FIND_PATH_REPLY [" + queryId + "] path=" + path);

        if (!predecessor.containsKey(queryId)) {
            JSONObject tp = new JSONObject();
            tp.put("msg_type", TRANSFER_PALLET);
            tp.put("target_path", path);
            tp.put("elapsed_ms", 0);

            ACLMessage toSelf = new ACLMessage(ACLMessage.REQUEST);
            toSelf.addReceiver(getAID());
            toSelf.setContent(tp.toJSONString());
            send(toSelf);

            broadcastAbort(queryId);
            return;
        }

        ACLMessage up = new ACLMessage(ACLMessage.INFORM);
        up.addReceiver(predecessor.get(queryId));
        up.setContent(js.toJSONString());
        send(up);
    }

    /** Clean up when a search is aborted. */
    private void handleAbort(ACLMessage m, JSONObject js) {
        String queryId = (String) js.get("query_id");
        bestCost.remove(queryId);
        predecessor.remove(queryId);
        for (String nb : neighbours) {
            if (!nb.equals(m.getSender().getLocalName())) {
                ACLMessage a = new ACLMessage(ACLMessage.INFORM);
                a.addReceiver(new AID(nb, AID.ISLOCALNAME));
                a.setContent(js.toJSONString());
                send(a);
            }
        }
    }
    private void broadcastAbort(String queryId) {
        JSONObject js = new JSONObject();
        js.put("msg_type", FIND_PATH_ABORT);
        js.put("query_id", queryId);
        for (String nb : neighbours) {
            ACLMessage a = new ACLMessage(ACLMessage.INFORM);
            a.addReceiver(new AID(nb, AID.ISLOCALNAME));
            a.setContent(js.toJSONString());
            send(a);
        }
    }

    /* ─── BUSY-too-long timeout triggers re-route ──────────────────── */
    private class BusyTimeout extends TickerBehaviour {
        BusyTimeout(Agent a, long period) { super(a, period); }
        protected void onTick() {
            if (state == BUSY &&
                    System.currentTimeMillis() - busySince > BUSY_TIMEOUT_MS) {
                if (currentDestination == null) {
                    log.warning(me + " busy timeout but no current destination");
                    return;
                }
                String queryId = UUID.randomUUID().toString();
                log.info(me + " busy timeout – reroute to " + currentDestination + "[" + queryId + "]");

                JSONObject req = new JSONObject();
                req.put("msg_type", FIND_PATH_REQ);
                req.put("query_id", queryId);
                req.put("dest", currentDestination);
                req.put("cost", 0);
                req.put("path", new JSONArray());

                ACLMessage r = new ACLMessage(ACLMessage.REQUEST);
                r.addReceiver(getAID());
                r.setContent(req.toJSONString());
                send(r);
            }
        }
    }

    /* ─── Helpers ─────────────────────────────────────────────────── */
    private void agree(ACLMessage m) { ACLMessage r = m.createReply(); r.setPerformative(ACLMessage.AGREE); send(r); }
    private void refuse(ACLMessage m, String why) { ACLMessage r = m.createReply(); r.setPerformative(ACLMessage.REFUSE); r.setContent(why); send(r); }
    private String stateName(int s) { return s == IDLE ? "IDLE" : s == BUSY ? "BUSY" : s == DOWN ? "DOWN" : "?"; }
}
