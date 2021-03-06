// SessionWorker.java
//

package ijava.kernel;

import java.io.*;
import java.util.*;
import ijava.extensibility.*;
import ijava.kernel.protocol.*;

/**
 * Processes tasks within the kernel session.
 */
public final class SessionWorker implements Runnable {

  private final static int SLEEP_INTERVAL = 500;

  private final Session _session;

  private final Queue<SessionTask> _tasks;
  private final Thread _thread;

  /**
   * Creates an instance of a SessionWorker.
   * @param session the associated session that this worker is part of.
   */
  public SessionWorker(Session session) {
    _session = session;
    _tasks = new LinkedList<SessionTask>();

    _thread = new Thread(this);
    _thread.setName("Worker");
    _thread.setDaemon(true);
  }

  /**
   * Adds a task to the worker's queue.
   * @param task
   */
  public void addTask(SessionTask task) {
    synchronized (_tasks) {
      _tasks.add(task);
    }
  }

  @SuppressWarnings("resource")
  private long processTask(SessionTask task, long counter) {
    Message parentMessage = task.getMessage();
    Map<String, Object> metadata = parentMessage.getMetadata();
    String content = task.getContent();

    if (content.isEmpty()) {
      Messages.ExecuteResponse response =
          new Messages.SuccessExecuteResponse(parentMessage.getIdentity(),
                                              parentMessage.getHeader(),
                                              counter,
                                              metadata);
      _session.sendMessage(response.associateChannel(parentMessage.getChannel()));

      // Nothing to execute, so return the counter without incrementing.
      return counter;
    }

    PrintStream stdout = System.out;
    PrintStream stderr = System.err;
    InputStream stdin = System.in;

    Throwable error = null;
    Object result = null;
    try {
      // Replace the standard streams.
      // Both stdout and stderr are published to the kernel client. The error output is buffered
      // so it doesn't get interspersed within the output, by getting broken up into incremental
      // blocks.
      // Ideally it would have been fine to interleave, and have the client UI split resulting
      // text spew across two different regions... but that doesn't seem to be the case in IPython.
      //
      // The stdin stream is simply disabled, i.e. fail fast if code attempts to read from it.
      PrintStream out;
      PrintStream err;

      if (task.requiresSilentProcessing()) {
        err = out = new PrintStream(new DisabledOutputStream());
      }
      else {
        out = new PrintStream(new PublishingOutputStream(Messages.StreamMessage.STDOUT,
                                                         parentMessage));
        err = new PrintStream(new PublishingOutputStream(Messages.StreamMessage.STDERR,
                                                         parentMessage,
                                                         /* autoFlush */ false));
      }
      System.setOut(out);
      System.setErr(err);
      System.setIn(new DisabledInputStream());

      long evaluationID = task.recordProcessing() ? counter : 0;
      result = _session.getEvaluator().evaluate(task.getContent(), evaluationID, metadata);
    }
    catch (EvaluationError e) {
      System.err.println(e.getMessage());
      error = e;
    }
    catch (Throwable t) {
      t.printStackTrace();
      error = t;
    }
    finally {
      // Flush the captured streams. This will send out any pending stream data to the client.
      System.out.flush();
      System.err.flush();

      System.setOut(stdout);
      System.setErr(stderr);
      System.setIn(stdin);
    }

    // Send a message to display the result, if there was any.
    if (result != null) {
      Map<String, String> data = _session.formatDisplayData(result);
      Messages.DataMessage dataMessage =
          new Messages.DataMessage(parentMessage.getIdentity(), parentMessage.getHeader(), data);
      _session.sendMessage(dataMessage.associateChannel(MessageChannel.Output));
    }

    // Send the success/failed result as a result of performing the task.
    Messages.ExecuteResponse response;
    if (error == null) {
      response =
          new Messages.SuccessExecuteResponse(parentMessage.getIdentity(),
                                              parentMessage.getHeader(),
                                              counter,
                                              metadata);
    }
    else {
      response =
          new Messages.ErrorExecuteResponse(parentMessage.getIdentity(), parentMessage.getHeader(),
                                            counter,
                                            error);
    }
    _session.sendMessage(response.associateChannel(parentMessage.getChannel()));

    return task.recordProcessing() ? counter + 1 : counter;
  }

  /**
   * Starts the task processing.
   */
  public void start() {
    _thread.start();
  }

  /**
   * Stops the task processing.
   */
  public void stop() {
    _thread.interrupt();
  }

  @Override
  public void run() {
    boolean busy = false;
    long counter = 1;

    while (!Thread.currentThread().isInterrupted()) {
      SessionTask task = null;
      synchronized (_tasks) {
        task = _tasks.poll();
      }

      if (task != null) {
        if (!busy) {
          // Transitioning from idle to busy
          busy = true;
          _session.sendMessage(Messages.KernelStatus.createBusyStatus());
        }

        counter = processTask(task, counter);
      }
      else {
        if (busy) {
          // Transitioning from busy to idle
          busy = false;
          _session.sendMessage(Messages.KernelStatus.createIdleStatus());
        }
      }

      // TODO: Synchronized access to check for empty?
      if (_tasks.isEmpty()) {
        try {
          Thread.sleep(SessionWorker.SLEEP_INTERVAL);
        }
        catch (InterruptedException e) {
        }
      }
    }
  }


  /**
   * Implements an OutputStream that publishes written bytes as out-going messages.
   */
  private final class PublishingOutputStream extends OutputStream {

    private final static int MAX_BUFFER_SIZE = 240;

    private final String _name;
    private final Message _parentMessage;
    private final StringBuilder _buffer;

    private final boolean _autoFlush;

    /**
     * Initializes a PublishingOutputStream instance with the stream name.
     * @param name the name of the stream.
     * @param parentMessage the associated message being processed.
     */
    public PublishingOutputStream(String name, Message parentMessage) {
      this(name, parentMessage, /* autoFlush */ true);
    }

    /**
     * Initializes a PublishingOutputStream instance with the stream name.
     * @param name the name of the stream.
     * @param parentMessage the associated message being processed.
     * @param autoFlush whether to automatically flush as text is written.
     */
    public PublishingOutputStream(String name, Message parentMessage, boolean autoFlush) {
      _name = name;
      _parentMessage = parentMessage;
      _autoFlush = autoFlush;

      _buffer = new StringBuilder(240);
    }

    @Override
    public void flush() {
      if (_buffer.length() != 0) {
        String text = _buffer.toString();
        _buffer.setLength(0);

        Messages.StreamMessage message =
            new Messages.StreamMessage(_parentMessage.getIdentity(), _parentMessage.getHeader(),
                                       _name, text);
        _session.sendMessage(message.associateChannel(MessageChannel.Output));
      }
    }

    @Override
    public void write(int b) throws IOException {
      if (_autoFlush && (_buffer.length() >= PublishingOutputStream.MAX_BUFFER_SIZE)) {
        flush();
      }

      _buffer.append((char)b);
    }
  }


  /**
   * Implements an OutputStream that discards all generated output.
   */
  private final class DisabledOutputStream extends OutputStream {

    @Override
    public void write(int b) throws IOException {
    }
  }


  /**
   * Implements an InputStream that has been disabled, i.e. cannot be read from. Attempts
   * to read result in an exception.
   */
  private final class DisabledInputStream extends InputStream {

    /**
     * {@link InputStream}
     */
    @Override
    public int read() throws IOException {
      String error = "Reading from System.in is not supported. " +
          "All input should be specified at the time of execution.";
      throw new UnsupportedOperationException(error);
    }
  }
}
