package net.sharksystem.android.protocols.routing;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.util.Log;

import net.sharkfw.asip.engine.ASIPConnection;
import net.sharkfw.asip.engine.ASIPInMessage;
import net.sharkfw.asip.engine.ASIPOutMessage;
import net.sharkfw.asip.engine.ASIPSerializer;
import net.sharkfw.knowledgeBase.PeerSTSet;
import net.sharkfw.knowledgeBase.PeerSemanticTag;
import net.sharkfw.knowledgeBase.STSet;
import net.sharkfw.knowledgeBase.SharkCSAlgebra;
import net.sharkfw.knowledgeBase.SharkKBException;
import net.sharkfw.knowledgeBase.inmemory.InMemoSharkKB;
import net.sharkfw.peer.ASIPPort;
import net.sharksystem.android.peer.AndroidSharkEngine;
import net.sharksystem.android.protocols.routing.db.MessageContentProvider;
import net.sharksystem.android.protocols.routing.db.MessageDTO;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

import static net.sharksystem.android.protocols.routing.service.RoutingService.KEY_SHARK;

// TODO what about the start of the routing? the original sender needs to insert the message he wants to send to the RouterKP's database somehow
public class RouterKP extends ASIPPort {

    //-----------------------------------------------------------------------------
    //------------------------------- Constants -----------------------------------
    //-----------------------------------------------------------------------------
    private static final String ROUTING_MESSAGE_ACCEPTED_STRING = "RoutingMessageAcceptedThisMessageShouldntBeSentByAUserOrHeHasAProblem";
    private static final String KEY_TOPICS_TO_ROUTE = KEY_SHARK + ".topicsToRoute";
    private static final String KEY_ROUTE_ANY_TOPICS = KEY_SHARK + ".routeAnyTopics";
    private static final String KEY_MAX_COPIES = KEY_SHARK + ".maxCopies";
    private static final String KEY_MESSAGE_TTL = KEY_SHARK + ".messageTtl";
    private static final String KEY_MESSAGE_TTL_UNIT = KEY_SHARK + ".messageTtlUnit";
    private static final String KEY_MESSAGE_CHECK_INTERVAL = KEY_SHARK + ".messageCheckInterval";

    //-----------------------------------------------------------------------------
    //------------------------------- Objects -------------------------------------
    //-----------------------------------------------------------------------------
    private SharedPreferences mPrefs;
    private Handler mHandler;
    private Runnable mRunnable;
    private AndroidSharkEngine mEngine;
    private MessageContentProvider mMessageContentProvider;

    //-----------------------------------------------------------------------------
    //------------------------- Configuration Parameters --------------------------
    //-----------------------------------------------------------------------------
    private STSet mTopicsToRoute;
    private PeerSTSet
    private boolean mRouteAnyTopics;
    private int mMaxCopies;
    private long mMessageTtl;
    private TimeUnit mMessageTtlUnit;
    private int mMessageCheckinterval;

    //-----------------------------------------------------------------------------
    //------------------------- Configuration Defaults-- --------------------------
    //-----------------------------------------------------------------------------
    private static final STSet DEFAULT_TOPICS_TO_ROUTE = InMemoSharkKB.createInMemoSTSet();
    private static final boolean DEFAULT_ROUTE_ANY_TOPICS = false;
    private static final int DEFAULT_MAX_COPIES = 10;
    private static final long DEFAULT_MESSAGE_TTL = 30;
    private static final TimeUnit DEFAULT_MESSAGE_TTL_UNIT = TimeUnit.SECONDS;
    private static final int MESSAGE_CHECK_INTERVAL = 2000;

    public RouterKP(AndroidSharkEngine engine, Context context) {
        super(engine);
        mEngine = engine;
        mPrefs = context.getSharedPreferences(KEY_SHARK, Context.MODE_PRIVATE);

        this.initConfiguration();

        mMessageContentProvider = new MessageContentProvider(context);

        mHandler = new Handler();
        mRunnable = new Runnable() {
            @Override
            public void run() {
                checkMessagesToRoute();
                mHandler.postDelayed(mRunnable, MESSAGE_CHECK_INTERVAL);
            }
        };
    }

    private void initConfiguration() {
        try {
            String defaultTopicsToRouteString = ASIPSerializer.serializeSTSet(DEFAULT_TOPICS_TO_ROUTE).toString();
            String topicsToRouteString = mPrefs.getString(KEY_TOPICS_TO_ROUTE, defaultTopicsToRouteString);
            mTopicsToRoute = ASIPSerializer.deserializeSTSet(topicsToRouteString);
        } catch (SharkKBException | JSONException e) {
            e.printStackTrace();
        }
        mRouteAnyTopics = mPrefs.getBoolean(KEY_ROUTE_ANY_TOPICS, DEFAULT_ROUTE_ANY_TOPICS);
        mMaxCopies = mPrefs.getInt(KEY_MAX_COPIES, DEFAULT_MAX_COPIES);
        mMessageTtl = mPrefs.getLong(KEY_MESSAGE_TTL, DEFAULT_MESSAGE_TTL);
        mMessageTtlUnit = TimeUnit.valueOf(mPrefs.getString(KEY_MESSAGE_TTL_UNIT, DEFAULT_MESSAGE_TTL_UNIT.toString()));
    }

    public void startRouting() {
        Log.e("ROUTERKP", "Routing started");
        mHandler.post(mRunnable);
    }

    public void stopRouting() {
        Log.e("ROUTERKP", "Routing stopped");
        mHandler.removeCallbacks(mRunnable);
    }

    // TODO message == connection ???
    @Override
    public boolean handleMessage(ASIPInMessage message, ASIPConnection connection) {
//        super.doProcess(msg, con);

        if (!mMessageContentProvider.doesMessageAlreadyExist(message)) {
            Log.e("ROUTERKP", "Persisting new message");
            mMessageContentProvider.persist(message);
        }

        boolean persist = false;
        boolean topicOk = false;
        boolean messageAlreadyStored = false;

        try {
            if (message.getTopic().isAny() && mRouteAnyTopics) {
                topicOk = true;
            } else if (mTopicsToRoute.isEmpty() || SharkCSAlgebra.isIn(mTopicsToRoute, message.getTopic())) {
                topicOk = true;
            }

            if (topicOk) {
                messageAlreadyStored = mMessageContentProvider.doesMessageAlreadyExist(message);
            }

            // TODO Spatial Routing, Peer Routing etc.
            if (topicOk && !messageAlreadyStored) {
                persist = true;

                if (persist) {
                    this.sendResponse(message, connection);
                    mMessageContentProvider.persist(message);
                }
            }
        } catch (SharkKBException e) {
            e.printStackTrace();
        }

        // TODO can other KP's still handle this if true is returned?
        return persist;
    }

    // TODO return response to the physical sender, not the sender peer, related to AndroidSharkEngine.sendMessage
    // TODO how to return a short response that says that this certain, UNIQUE ASIPInMessage gets further routed by this RouterKP?
    // TODO implement method that waits for that response
    private void sendResponse(final ASIPInMessage message, final ASIPConnection connection) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                ASIPOutMessage response = mEngine.createASIPOutMessage(message.getSender().getAddresses(), message.getSender());
                response.raw(ROUTING_MESSAGE_ACCEPTED_STRING.getBytes());
            }
        }).start();
    }

    // TODO Spatial Routing, Peer Routing etc.
    private void checkMessagesToRoute() {
        Log.e("ROUTERKP", "Checking messages");
        // TODO cache messages
        List<MessageDTO> allMessages = mMessageContentProvider.getAllMessages();

        if (allMessages.size() > 0) {
            for (int i = allMessages.size() - 1; i >= 0; i--) {
                MessageDTO message = allMessages.get(i);

                if (message.getReceiverPeer() != null) {
                    this.checkReceiverPeer(message);
                } else {
                    this.broadcastMessage(message);
                }

                this.checkMessageLifeTime(message);
            }
        }
    }

    private void checkReceiverPeer(MessageDTO message) {
        List<PeerSemanticTag> nearbyPeers = mEngine.getNearbyPeers();
        PeerSemanticTag receiver = message.getReceiverPeer();

        for (PeerSemanticTag peer : nearbyPeers) {
            if (peer.identical(receiver)) {
                mEngine.sendMessage(message, message.getReceiverPeer().getAddresses());
                mMessageContentProvider.delete(message);
                return;
            }
        }

        //Receiver is not nearby, so try to send it to as many new ppl as possible
        this.broadcastMessage(message);
    }
    private void broadcastMessage(MessageDTO message) {
        String[] nearbyPeerTCPAddresses = mEngine.getNearbyPeerTCPAddresses();
        List<String> previousReceiverAdresses = mMessageContentProvider.getReceiverAddresses(message);
        List<String> addressesToSend = new ArrayList<>();

        for (String address : nearbyPeerTCPAddresses) {
            if (!previousReceiverAdresses.contains(address)) {
                addressesToSend.add(address);
            }
        }

        if (addressesToSend.size() > 0) {
            // TODO Replace ROUTERKP with standard tags
            Log.e("ROUTERKP", "Broadcasting message to " + addressesToSend.size() + " addresses.");
            mEngine.sendMessage(message, addressesToSend.toArray(new String[addressesToSend.size()]));

            // TODO update sentCopies only after routing response
            long sentCopies = message.getSentCopies() + addressesToSend.size();
            if (sentCopies > mMaxCopies) {
                Log.e("ROUTERKP", "Deleting message because max copies number exceeded");
                mMessageContentProvider.delete(message);
            } else {
                message.setSentCopies(sentCopies);
                mMessageContentProvider.updateReceiverAddresses(message, addressesToSend);
                mMessageContentProvider.update(message);
            }
        }
    }

    private void checkMessageLifeTime(MessageDTO message) {
        long now = System.currentTimeMillis();
        if (now > message.getInsertionDate() + mMessageTtlUnit.toMilliseconds(mMessageTtl)) {
            Log.e("ROUTERKP", "Deleting message because it's life is over");
            mMessageContentProvider.delete(message);
        }
    }

    public STSet getTopicsToRoute() {
        return mTopicsToRoute;
    }

    public void setTopicsToRoute(STSet topics) {
        mTopicsToRoute = topics;
        try {
            String topicsToRouteString = ASIPSerializer.serializeSTSet(topics).toString();
            mPrefs.edit().putString(KEY_TOPICS_TO_ROUTE, topicsToRouteString).apply();
        } catch (SharkKBException | JSONException e) {
            e.printStackTrace();
        }
    }

    public boolean getRouteAnyTopics() {
        return mRouteAnyTopics;
    }

    public void setRouteAnyTopics(boolean routeAnyTopics) {
        mRouteAnyTopics = routeAnyTopics;
        mPrefs.edit().putBoolean(KEY_ROUTE_ANY_TOPICS, routeAnyTopics).apply();
    }

    public int getMessageCheckInterval() {
        return mMessageCheckinterval;
    }

    public void setMessageCheckInterval(int messageCheckInterval) {
        mMessageCheckinterval = messageCheckInterval;
        mPrefs.edit().putInt(KEY_MESSAGE_CHECK_INTERVAL, messageCheckInterval).apply();
    }

    public TimeUnit getMessageTtlUnit() {
        return mMessageTtlUnit;
    }

    public void setMessageTtlUnit(TimeUnit messageTtlUnit) {
        mMessageTtlUnit = messageTtlUnit;
        mPrefs.edit().putString(KEY_MESSAGE_TTL_UNIT, messageTtlUnit.toString()).apply();
    }

    public long getMessageTtl() {
        return mMessageTtl;
    }

    public void setMessageTtl(long messageTtl) {
        mMessageTtl = messageTtl;
        mPrefs.edit().putLong(KEY_MESSAGE_TTL, messageTtl).apply();
    }

    public int getMaxCopies() {
        return mMaxCopies;
    }

    public void setMaxCopies(int maxCopies) {
        mMaxCopies = maxCopies;
        mPrefs.edit().putInt(KEY_MAX_COPIES, maxCopies).apply();
    }
}
