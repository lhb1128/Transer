package com.scott.transer.task;

import com.scott.annotionprocessor.ITask;
import com.scott.annotionprocessor.TaskType;
import com.scott.transer.utils.Debugger;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * <p>Author:    shijiale</p>
 * <p>Date:      2017-12-14 15:31</p>
 * <p>Email:     shilec@126.com</p>
 * <p>Describe:
 *      如果实现其他的传输器需要继承自该类
 * </p>
 */
public abstract class BaseTaskHandler implements ITaskHandler {

    protected ITaskHandlerListenner mListenner;
    private volatile boolean isExit = false;
    private Map<String, String> mParams;
    private Map<String, String> mHeaders;
    private ExecutorService mTaskHandleThreadPool;
    private volatile Task mTask;
    private HandleRunnable mHandleRunnable;
    private final String TAG = BaseTaskHandler.class.getSimpleName();

    public BaseTaskHandler() {
        mHandleRunnable = new HandleRunnable();
    }

    @Override
    public void setHandlerListenner(ITaskHandlerListenner l) {
        mListenner = l;
    }

    @Override
    public Map<String, String> getHeaders() {
        return mHeaders;
    }

    @Override
    public Map<String, String> getParams() {
        return mParams;
    }

    @Override
    public void setHeaders(Map<String, String> headers) {
        mHeaders = headers;
    }

    @Override
    public void setParams(Map<String, String> params) {
        mParams = params;
    }

    @Override
    public void setThreadPool(ExecutorService threadPool) {
        mTaskHandleThreadPool = threadPool;
    }

    @Override
    public void setState(int state) { //user call
        _internalSetState(state);
    }

    private void _internalSetState(int state) {
        switch (state) {
            case TaskState.STATE_START:
                start();
                break;
            case TaskState.STATE_STOP:
                stop();
                break;
            case TaskState.STATE_PAUSE:
                pause();
                break;
            case TaskState.STATE_RESUME:
                resume();
                break;
        }
    }

    //判断一片是否发送或接受成功
    protected  abstract boolean isPiceSuccessful();

    //判断任务是否成功
    protected abstract boolean isSuccessful();

    //从数据源中读取一片
    protected abstract byte[] readPice(Task task) throws Exception;

    //写入一片到目标
    protected abstract void writePice(byte[] datas,Task task) throws Exception;

    //传输开始前
    protected abstract void prepare(ITask task) throws Exception;

    //当前这片从数据源中实际读取的大小
    protected abstract int getPiceRealSize();

    protected  abstract long fileSize();

    private void handle(ITask task) throws Exception {

        ((Task)task).setStartOffset(task.getCompleteLength()); //每次开始之前设置起始偏移量
        //开始任务前准备任务数据，初始化源数据流
        prepare(task);
        //获取到的源数据大小设置到task
        ((Task) task).setLength(fileSize());
        Debugger.info(TAG,"start ============= length = " + task.getLength() + "" +
                ",completeLength = " + task.getCompleteLength() + ",startOffset = " + task.getStartOffset() + ",endOffset = " + task.getEndOffset());

        while (!isExit) {
            Debugger.info(TAG,"length = " + task.getLength() + "" +
                    ",completeLength = " + task.getCompleteLength() + ",startOffset = " + task.getStartOffset() + ",endOffset = " + task.getEndOffset());
            byte[] datas = readPice((Task) task); // 从源中读取一片数据
            int piceSize = getPiceRealSize(); //获取当前读取一片的实际大小

            //如果读取到源数据的末尾
            if(piceSize == -1) {
                isExit = true;
                break;
            }
            //设置读取的结束偏移量
            ((Task) task).setEndOffset(task.getStartOffset() + piceSize);
            writePice(datas,(Task) task); //写入实际读入的大小
            mTask.setCompleteLength(mTask.getEndOffset());
            mTask.setStartOffset(mTask.getEndOffset());

            if(isPiceSuccessful()) { //判断一片是否成功
                mListenner.onPiceSuccessful(mTask);
            } else {
                mTask.setState(TaskState.STATE_ERROR);
                mListenner.onError(TaskErrorCode.ERROR_PICE,mTask);
                isExit = true;
                break;
            }
        }

        if(!isSuccessful()) { //判断整个任务是否成功
            mTask.setState(TaskState.STATE_ERROR);
            mListenner.onError(TaskErrorCode.ERROR_FINISH,mTask);
        } else {
            mTask.setState(TaskState.STATE_FINISH);
            mListenner.onFinished(mTask);
        }

        release(); //释放资源
    }

    @Override
    public void start() {
        synchronized (this) {
            //如果任务已经开始或完成则不重复开始
            if(mTask.getState() == TaskState.STATE_START ||
                    mTask.getState() != TaskState.STATE_FINISH) {
                //throw new IllegalStateException("current handler already started ...");
                Debugger.error(TAG,"current handler already started ...");
                return;
            }

            //如果是恢复任务，则释放资源(如果不释放，网络流可能超时无法使用)
            release();

            //如果设置了线程池则会在线程池中传输，否则会在当前线程中开始传输
            if (mTaskHandleThreadPool != null) {
                mTaskHandleThreadPool.execute(mHandleRunnable);
            } else {
                mHandleRunnable.run();
            }
            mListenner.onStart(mTask);
            mTask.setState(TaskState.STATE_START);
        }
    }

    @Override
    public void stop() {

        //停止，完成,失败的任务不能停止
        if(TaskState.STATE_STOP == mTask.getState() ||
                TaskState.STATE_FINISH == mTask.getState() ||
                TaskState.STATE_ERROR == mTask.getState()) {
            return;
        }
        isExit = true;
        mTask.setState(TaskState.STATE_STOP);
        mListenner.onStop(mTask);
    }

    @Override
    public void pause() {

        //停止，完成，暂停，失败 的任务不能暂停
        if(TaskState.STATE_PAUSE == mTask.getState() ||
                TaskState.STATE_FINISH == mTask.getState() ||
                TaskState.STATE_STOP == mTask.getState() ||
                TaskState.STATE_ERROR == mTask.getState()) {
            return;
        }
        mTask.setState(TaskState.STATE_PAUSE);
        isExit = true;
        mListenner.onPause(mTask);
        Debugger.info(TAG,"pause ============= length = " + mTask.getLength() + "" +
                ",completeLength = " + mTask.getCompleteLength() + ",startOffset = " + mTask.getStartOffset() + ",endOffset = " + mTask.getEndOffset());
    }

    @Override
    public void resume() {
        Debugger.info(TAG,"resume ============= length = " + mTask.getLength() + "" +
                ",completeLength = " + mTask.getCompleteLength() + ",startOffset = " + mTask.getStartOffset() + ",endOffset = " + mTask.getEndOffset());

        //开始 和 恢复的任务不能恢复
        if(mTask.getState() == TaskState.STATE_START ||
                mTask.getState() == TaskState.STATE_RESUME) {
            return;
        }

        if(mHandleRunnable != null) {
            mHandleRunnable = new HandleRunnable();
        }
        isExit = false;
        mTask.setState(TaskState.STATE_RESUME);
        start();
        mListenner.onResume(mTask);
    }

    protected void release() {

    }

    @Override
    public int getState() {
        return mTask.getState();
    }

    @Override
    public ITask getTask() {
        return mTask;
    }

    @Override
    public void setTask(ITask task) {
        mTask = (Task) task;
    }

    @Override
    public TaskType getType() {
        return mTask.getType();
    }

    class HandleRunnable implements Runnable {

        @Override
        public void run() {
            try {
                handle(mTask);
            } catch (Exception e) {
                e.printStackTrace();
                mTask.setState(TaskState.STATE_ERROR);
                mListenner.onError(TaskErrorCode.ERROR_CODE_EXCEPTION,mTask);
            }
        }
    }
}