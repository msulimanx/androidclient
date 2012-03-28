/*
 * Kontalk Android client
 * Copyright (C) 2011 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;

import org.kontalk.client.EndpointServer;
import org.kontalk.client.MessageSender;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.os.Process;
import android.os.MessageQueue.IdleHandler;
import android.util.Log;


/**
 * Manages a queue of outgoing requests, including messages to be sent.
 * It also creates a {@link ClientThread} to handle the actual connection and
 * incoming messages/packs.
 * @author Daniele Ricci
 */
public class RequestWorker extends HandlerThread implements ParentThread {
    private static final String TAG = RequestWorker.class.getSimpleName();
    private static final int MSG_REQUEST_JOB = 1;
    private static final int MSG_IDLE = 2;

    private static final long DEFAULT_RETRY_DELAY = 10000;

    private PauseHandler mHandler;

    private final Context mContext;

    private Boolean mIdle = false;
    private boolean mInterrupted;

    /** Reference counter - will be passed to {@link PauseHandler}. */
    private int mRefCount;

    private ClientThread mClient;
    private RequestListenerList mListeners = new RequestListenerList();

    /** Pending jobs queue - will be used on thread start to initialize the messages. */
    static public LinkedList<RequestJob> pendingJobs = new LinkedList<RequestJob>();

    public RequestWorker(Context context, EndpointServer server, int refCount) {
        super(RequestWorker.class.getSimpleName(), Process.THREAD_PRIORITY_BACKGROUND);
        mContext = context;
        mRefCount = refCount;
        mClient = new ClientThread(context, this, server);
    }

    public void addListener(RequestListener listener) {
        if (!this.mListeners.contains(listener))
            this.mListeners.add(listener);
    }

    public void removeListener(RequestListener listener) {
        this.mListeners.remove(listener);
    }

    /** Client thread has terminated. */
    @Override
    public void childTerminated(int reason) {
    }

    @Override
    protected void onLooperPrepared() {
        // create handler and empty pending jobs queue
        // this must be done synchronized on the queue
        synchronized (pendingJobs) {
            mHandler = new PauseHandler(new LinkedList<RequestJob>(pendingJobs), mRefCount);
            pendingJobs = new LinkedList<RequestJob>();
        }
    }

    @Override
    public void interrupt() {
        super.interrupt();
        mInterrupted = true;
    }

    @Override
    public boolean isInterrupted() {
        return mInterrupted;
    }

    /** A fake listener to call all the listeners inside the collection. */
    private final class RequestListenerList extends ArrayList<RequestListener>
            implements RequestListener {
        private static final long serialVersionUID = 1L;

        @Override
        public void downloadProgress(ClientThread client, RequestJob job, long bytes) {
            for (RequestListener l : this)
                l.downloadProgress(client, job, bytes);
        }

        @Override
        public boolean error(ClientThread client, RequestJob job, Throwable exc) {
            boolean requeue = false;
            for (RequestListener l : this)
                if (l.error(client, job, exc))
                    requeue = true;

            return requeue;
        }

        @Override
        public void done(ClientThread client, RequestJob job, String txId) {
            for (RequestListener l : this) {
                l.done(client, job, txId);
            }
        }

        @Override
        public void uploadProgress(ClientThread client, RequestJob job, long bytes) {
            for (RequestListener l : this)
                l.uploadProgress(client, job, bytes);
        }
    }

    private final class PauseHandler extends Handler implements IdleHandler {
        /** How much time to wait to idle the message center. */
        private final int IDLE_MSG_TIME = 5000; //60000;
        /** Reference counter. */
        private int mRefCount;

        public PauseHandler(Queue<RequestJob> pending, int refCount) {
            // no need to super(), will use looper from the current thread

            this.mRefCount = refCount;

            // set idle handler
            Log.v(TAG, "setting idle handler");
            if (this.mRefCount <= 0)
                Looper.myQueue().addIdleHandler(this);

            // requeue the old messages
            Log.d(TAG, "processing pending jobs queue (" + pending.size() + " jobs)");
            for (RequestJob job = pending.poll(); job != null; job = pending.poll()) {
                //Log.d(TAG, "requeueing pending job " + job);
                sendMessage(obtainMessage(MSG_REQUEST_JOB, job));
            }
        }

        /** Schedules idleMessageCenter() after IDLE_MSG_TIME ms. */
        @Override
        public boolean queueIdle() {
            // remove the idle message anyway
            removeMessages(MSG_IDLE);

            Log.d(TAG, "no more messages (ref=" + mRefCount + ")");
            if (mRefCount <= 0)
                sendMessageDelayed(obtainMessage(MSG_IDLE), IDLE_MSG_TIME);

            return true;
        }

        @Override
        public void handleMessage(Message msg) {
            // something to work out
            if (msg.what == MSG_REQUEST_JOB) {
                // remove any pending idle messages
                removeMessages(MSG_IDLE);

                // not running - queue message
                if (mInterrupted) {
                    Log.i(TAG, "request worker is not running - dropping message");
                    return;
                }

                // still not connected - requeue message after 500 ms
                if (mClient == null || mClient.getConnection() == null) {
                    Log.v(TAG, "client not ready - retrying message in 500 ms");
                    sendMessageDelayed(obtainMessage(msg.what, msg.obj), 500);
                    return;
                }

                RequestJob job = (RequestJob) msg.obj;
                Log.d(TAG, "JOB: " + job.toString());

                // check now if job has been canceled
                if (job.isCanceled()) {
                    Log.i(TAG, "request has been canceled - dropping");
                    return;
                }

                // try to use the custom listener
                RequestListener listener = job.getListener();
                if (listener != null)
                    addListener(listener);

                // synchronize on client pack lock
                synchronized (mClient.getPackLock()) {

                    try {
                        // FIXME this should be abstracted/delegated some way
                        if (job instanceof MessageSender) {
                            MessageSender mess = (MessageSender) job;
                            // observe the content for cancel requests
                            mess.observe(mContext, this);
                        }

                        String txId = job.execute(mClient, mListeners, mContext);

                        mListeners.done(mClient, job, txId);
                    }
                    catch (IOException e) {
                        if (mInterrupted) {
                            Log.v(TAG, "worker has been interrupted");
                            return;
                        }

                        boolean requeue = true;
                        Log.e(TAG, "request error", e);
                        requeue = mListeners.error(mClient, job, e);

                        if (requeue) {
                            Log.d(TAG, "requeuing job " + job);
                            push(job, DEFAULT_RETRY_DELAY);
                        }
                    }
                    finally {
                        // unobserve if necessary
                        if (job != null && job instanceof MessageSender) {
                            MessageSender mess = (MessageSender) job;
                            mess.unobserve(mContext);
                        }

                        // remove our old custom listener
                        if (listener != null)
                            removeListener(listener);
                    }
                }
            }

            // idle message
            else if (msg.what == MSG_IDLE) {
                MessageCenterService.idleMessageCenter(mContext);
            }

            else
                super.handleMessage(msg);
        }

        public void hold() {
            this.mRefCount++;
            post(new Runnable() {
                @Override
                public void run() {
                    Log.v(TAG, "removing idle handler");
                    Looper.myQueue().removeIdleHandler(mHandler);
                    removeMessages(MSG_IDLE);
                }
            });
        }

        public void release() {
            this.mRefCount--;
            if (mRefCount <= 0) {
                mRefCount = 0;
                post(new Runnable() {
                    @Override
                    public void run() {
                        Log.v(TAG, "adding idle handler");
                        removeMessages(MSG_IDLE);
                        Looper.myQueue().addIdleHandler(mHandler);
                    }
                });
            }
        }
    }

    public void push(RequestJob job) {
        push(job, 0);
    }

    public void push(RequestJob job, long delayMillis) {
        synchronized (mIdle) {
            // max wait time 10 seconds
            int retries = 20;

            while(!isAlive() || mHandler == null || retries <= 0) {
                try {
                    // 500ms should do the job...
                    Thread.sleep(500);
                    Thread.yield();
                    retries--;
                } catch (InterruptedException e) {
                    // interrupted - do not send message
                    return;
                }
            }

            mHandler.sendMessageDelayed(
                    mHandler.obtainMessage(MSG_REQUEST_JOB, job),
                    delayMillis);

            // abort any idle request
            if (mIdle) {
                MessageCenterService.startMessageCenter(mContext);
                mIdle = false;
            }
        }
    }

    /** Returns true if the worker is running. */
    public boolean isRunning() {
        return (mHandler != null && !mInterrupted);
    }

    /** Shuts down this request worker gracefully. */
    public synchronized void shutdown() {
        Log.d(TAG, "shutting down");
        interrupt();

        Log.d(TAG, "quitting looper");
        quit();
        // do not join - just discard the thread

        if (mClient != null) {
            mClient.shutdown();
            mClient = null;
        }

        Log.d(TAG, "exiting");
    }

    /** Schedules request worker exit as soon as possible. */
    public void idle() {
        mIdle = true;
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                Looper.myQueue().addIdleHandler(new MessageQueue.IdleHandler() {
                    @Override
                    public boolean queueIdle() {
                        synchronized (mIdle) {
                            if (mIdle)
                                MessageCenterService
                                    .stopMessageCenter(mContext);
                        }
                        return false;
                    }
                });
            }
        });
    }

    public ClientThread getClient() {
        return mClient;
    }

    public void hold() {
        if (mHandler != null)
            mHandler.hold();
        else
            mRefCount++;
    }

    public void release() {
        if (mHandler != null)
            mHandler.release();
        else
            mRefCount--;
    }

    @Override
    public void run() {
        mClient.start();
        super.run();
    }

}