/*
 * Created on Tuesday, May 03 2011
 */
package com.jogamp.opencl.util.concurrent;

import com.jogamp.opencl.CLCommandQueue;
import com.jogamp.opencl.CLDevice;
import com.jogamp.opencl.CLResource;
import com.jogamp.opencl.util.CLMultiContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * A multithreaded fixed size pool of OpenCL command queues.
 * It serves as a multiplexer distributing tasks over N queues.
 * The usage of this pool is similar to {@link ExecutorService} but it uses {@link CLTask}s
 * instead of {@link Callable}s.
 * @author Michael Bien
 */
public class CLCommandQueuePool implements CLResource {

    private final List<CLQueueContext> contexts;
    private ExecutorService excecutor;
    private FinishAction finishAction = FinishAction.DO_NOTHING;

    private CLCommandQueuePool(Collection<CLQueueContext> contexts) {
        this.contexts = Collections.unmodifiableList(new ArrayList<CLQueueContext>(contexts));
        initExecutor();
    }

    private void initExecutor() {
        this.excecutor = Executors.newFixedThreadPool(contexts.size(), new QueueThreadFactory(contexts));
    }

    public static CLCommandQueuePool create(CLQueueContextFactory factory, CLMultiContext mc, CLCommandQueue.Mode... modes) {
        return create(factory, mc.getDevices(), modes);
    }

    public static CLCommandQueuePool create(CLQueueContextFactory factory, Collection<CLDevice> devices, CLCommandQueue.Mode... modes) {
        List<CLCommandQueue> queues = new ArrayList<CLCommandQueue>(devices.size());
        for (CLDevice device : devices) {
            queues.add(device.createCommandQueue(modes));
        }
        return create(factory, queues);
    }

    public static CLCommandQueuePool create(CLQueueContextFactory factory, Collection<CLCommandQueue> queues) {
        List<CLQueueContext> contexts = new ArrayList<CLQueueContext>(queues.size());
        for (CLCommandQueue queue : queues) {
            contexts.add(factory.setup(queue, null));
        }
        return new CLCommandQueuePool(contexts);
    }

    public <R> Future<R> submit(CLTask<R> task) {
        return excecutor.submit(new TaskWrapper(task, finishAction));
    }

    public <R> List<Future<R>> invokeAll(Collection<CLTask<R>> tasks) throws InterruptedException {
        List<TaskWrapper<R>> wrapper = new ArrayList<TaskWrapper<R>>(tasks.size());
        for (CLTask<R> task : tasks) {
            wrapper.add(new TaskWrapper<R>(task, finishAction));
        }
        return excecutor.invokeAll(wrapper);
    }

    /**
     * Calls {@link CLCommandQueue#flush()} on all queues.
     */
    public void flushQueues() {
        for (CLQueueContext context : contexts) {
            context.queue.flush();
        }
    }

    /**
     * Calls {@link CLCommandQueue#finish()} on all queues.
     */
    public void finishQueues() {
        for (CLQueueContext context : contexts) {
            context.queue.finish();
        }
    }

    /**
     * Releases all queues.
     */
    public void release() {
        excecutor.shutdown();
        for (CLQueueContext context : contexts) {
            context.queue.finish().release();
        }
    }

    /**
     * Returns the command queues used in this pool.
     */
    public List<CLCommandQueue> getQueues() {
        List<CLCommandQueue> queues = new ArrayList<CLCommandQueue>(contexts.size());
        for (CLQueueContext context : contexts) {
            queues.add(context.queue);
        }
        return queues;
    }

    /**
     * Returns the size of this pool (number of command queues).
     */
    public int getSize() {
        return contexts.size();
    }

    public FinishAction getFinishAction() {
        return finishAction;
    }

    public void setFinishAction(FinishAction action) {
        this.finishAction = action;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()+" [queues: "+contexts.size()+" on finish: "+finishAction+"]";
    }

    private static class QueueThreadFactory implements ThreadFactory {

        private final List<CLQueueContext> context;
        private int index;

        private QueueThreadFactory(List<CLQueueContext> queues) {
            this.context = queues;
            this.index = 0;
        }

        public synchronized Thread newThread(Runnable r) {
            CLQueueContext queue = context.get(index);
            return new QueueThread(queue, index++);
        }

    }
    
    private static class QueueThread extends Thread {
        private final CLQueueContext context;
        public QueueThread(CLQueueContext context, int index) {
            super("queue-worker-thread-"+index+"["+context+"]");
            this.context = context;
            this.setDaemon(true);
        }
    }

    private static class TaskWrapper<T> implements Callable<T> {

        private final CLTask<T> task;
        private final FinishAction mode;
        
        public TaskWrapper(CLTask<T> task, FinishAction mode) {
            this.task = task;
            this.mode = mode;
        }

        public T call() throws Exception {
            CLQueueContext context = ((QueueThread)Thread.currentThread()).context;
            T result = task.run(context);
            if(mode.equals(FinishAction.FLUSH)) {
                context.queue.flush();
            }else if(mode.equals(FinishAction.FINISH)) {
                context.queue.finish();
            }
            return result;
        }

    }

    /**
     * The action executed after a task completes.
     */
    public enum FinishAction {

        /**
         * Does nothing, the task is responsible to make sure all computations
         * have finished when the task finishes
         */
        DO_NOTHING,

        /**
         * Flushes the queue on task completion.
         */
        FLUSH,
        
        /**
         * Finishes the queue on task completion.
         */
        FINISH
    }

}
