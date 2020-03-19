package com.appland.appmap.process;

import com.appland.appmap.output.v1.Event;
import com.appland.appmap.output.v1.Value;
import com.appland.appmap.record.ActiveSessionException;
import com.appland.appmap.record.IRecordingSession;
import com.appland.appmap.record.Recorder;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletRequest;

/**
 * HttpServletReceiver hooks the method <code>javax.servlet.http.HttpServlet#service</code>. If the
 * request route is the remote recording path, the request is hijacked and interpreted as a remote
 * recording command. Otherwise, it's recorded as an appmap event, and processed by the application
 * services.
 *
 * @see RecordRoute
 */
public class HttpServletReceiver implements IEventProcessor {
  public static final String RecordRoute = "/_appmap/record";
  private static final Recorder recorder = Recorder.getInstance();

  private void doDelete(HttpServletRequest req, HttpServletResponse res) {
    try {
      String json = recorder.stop();
      res.setContentType("application/json");
      res.setContentLength(json.length());

      PrintWriter writer = res.getWriter();
      writer.write(json);
      writer.flush();
    } catch (ActiveSessionException e) {
      res.setStatus(HttpServletResponse.SC_NOT_FOUND);
    } catch (IOException e) {
      System.err.printf("failed to write response: %s\n", e.getMessage());
    }
  }

  private void doGet(HttpServletRequest req, HttpServletResponse res) {
    res.setStatus(HttpServletResponse.SC_OK);

    String responseJson = String.format("{\"enabled\":%b}", recorder.hasActiveSession());
    res.setContentType("application/json");
    res.setContentLength(responseJson.length());

    try {
      PrintWriter writer = res.getWriter();
      writer.write(responseJson);
      writer.flush();
    } catch (IOException e) {
      System.err.printf("failed to write response: %s\n", e.getMessage());
    }
  }

  private void doPost(HttpServletRequest req, HttpServletResponse res) {
    IRecordingSession.Metadata metadata = new IRecordingSession.Metadata();
    metadata.recorderName = "remote_recording";
    try {
      recorder.start(metadata);
    } catch (ActiveSessionException e) {
      res.setStatus(HttpServletResponse.SC_CONFLICT);
    }
  }

  private Boolean handleRequest(Event event) {
    if (event.event.equals("call") == false) {
      return false;
    }

    Value requestValue = event.getParameter(0);
    Value responseValue = event.getParameter(1);
    if (requestValue == null || responseValue == null) {
      return false;
    }

    if (!(requestValue.get() instanceof HttpServletRequest)) {
      System.err.printf("servlet request value %s is not an HttpServletRequest\n",
          requestValue.get().getClass().getName());
      return false;
    }

    HttpServletRequest req = requestValue.get();
    HttpServletResponse res = responseValue.get();

    if (req.getRequestURI().equals(RecordRoute) == false) {
      return false;
    }

    switch (req.getMethod()) {
      case "DELETE": {
        this.doDelete(req, res);
        break;
      }
      case "GET": {
        this.doGet(req, res);
        break;
      }
      case "POST": {
        this.doPost(req, res);
        break;
      }
      default: {
        return false;
      }
    }

    return true;
  }

  @Override
  public Boolean onEnter(Event event) {
    Boolean shouldContinueExecution = (this.handleRequest(event) == false);
    return shouldContinueExecution;
  }

  @Override
  public void onExit(Event event) {
    // do nothing
  }
}
