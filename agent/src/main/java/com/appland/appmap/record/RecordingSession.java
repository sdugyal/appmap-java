package com.appland.appmap.record;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import com.alibaba.fastjson.JSON;
import com.appland.appmap.output.v1.CodeObject;
import com.appland.appmap.output.v1.Event;
import com.appland.appmap.util.Logger;

public class RecordingSession {

  private final HashSet<String> classReferences = new HashSet<>();
  private boolean eventReceived = false;
  private Path tmpPath;
  private AppMapSerializer serializer;
  private final Recorder.Metadata metadata;
  private final Map<Integer, Event>eventUpdates = new HashMap<Integer, Event>();

  RecordingSession(Recorder.Metadata metadata) {
    this.tmpPath = null;
    this.metadata = metadata;

    start();
  }

  public Recorder.Metadata getMetadata() {
    return this.metadata;
  }

  public synchronized void add(Event event) {
    this.eventReceived = true;
    if ( event.event.equals("call") ) {
      // Events may refer to non-code objects such as SQL queries, in that case we don't
      // need to worry about tracking class references.
      if ( event.definedClass != null && event.methodId != null ) {
        String key = event.definedClass +
            ":" + event.methodId +
            ":" + event.isStatic +
            ":" + event.lineNumber;
        this.classReferences.add(key);
      }
    }

    try {
      this.serializer.writeEvents(Collections.singletonList(event));
    } catch (IOException e) {
      throw new ActiveSessionException(String.format("Failed to flush recording session:\n%s\n", e.getMessage()), e);
    }
  }

  public synchronized void addEventUpdate(Event event) {
    Logger.println("addEventUpdate, event: " + JSON.toJSONString(event));
    this.eventUpdates.put(event.id, event);
  }

  public synchronized Recording checkpoint() {
    if (this.serializer == null) {
      throw new IllegalStateException("AppMap: Unable to checkpoint the recording because no recording is in progress.");
    }

    Path targetPath;
    try {
      this.serializer.flush();

      targetPath = Files.createTempFile(null, ".appmap.json");
      Files.copy(this.tmpPath, targetPath, StandardCopyOption.REPLACE_EXISTING);

      // Creating AppMapSerializer will re-write the "begin object" token: '{'.
      // By using RandomAccessFile we can erase that character.
      // If we don't let the JSON writer write the "begin object" token, it refuses
      // to do anything else properly either.
      RandomAccessFile raf = new RandomAccessFile(targetPath.toFile(), "rw");
      Writer fw = new OutputStreamWriter(new OutputStream() {
        @Override
        public void write(int b) throws IOException {
          raf.write(b);
        }
      });
      raf.seek(targetPath.toFile().length());

      if (  eventReceived ) {
        fw.write("],");
      }
      fw.flush();

      AppMapSerializer serializer = AppMapSerializer.reopen(fw, raf);
      serializer.writeClassMap(this.getClassMap());
      serializer.writeMetadata(this.metadata);
      serializer.writeEventUpdates(this.eventUpdates);
      serializer.finish();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    Logger.printf("AppMap: Recording flushed at checkpoint\n");
    Logger.printf("AppMap: Wrote recording to file %s\n", targetPath);

    return new Recording(this.metadata.recorderName, targetPath.toFile());
  }

  public synchronized Recording stop() {
    if (this.serializer == null) {
      throw new IllegalStateException("AppMap: Unable to stop the recording because no recording is in progress.");
    }

    try {
      this.serializer.writeClassMap(this.getClassMap());
      this.serializer.writeMetadata(this.metadata);
      this.serializer.writeEventUpdates(this.eventUpdates);
      this.serializer.finish();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    File file = this.tmpPath.toFile();
    this.serializer = null;
    this.tmpPath = null;

    Logger.printf("AppMap: Recording finished\n");
    Logger.printf("AppMap: Wrote recording to file %s\n", file.getPath());

    return new Recording(metadata.recorderName, file);
  }

  CodeObjectTree getClassMap() {
    CodeObjectTree registeredObjects = Recorder.getInstance().getRegisteredObjects();
    CodeObjectTree classMap = new CodeObjectTree();
    for (String key : this.classReferences) {
      String[] parts = key.split(":");

      CodeObject methodBranch = registeredObjects.getMethodBranch(parts[0], parts[1], Boolean.valueOf(parts[2]), Integer.valueOf(parts[3]));
      if (methodBranch != null)
        classMap.add(methodBranch);
    }

    return classMap;
  }

  void start() {
    if (this.serializer != null) {
      throw new IllegalStateException("AppMap: Unable to start a recording, because a recording is already in progress");
    }

    try {
      this.tmpPath = Files.createTempFile(null, ".appmap.json");
      this.tmpPath.toFile().deleteOnExit();
      this.serializer = AppMapSerializer.open(new FileWriter(this.tmpPath.toFile()));
    } catch (IOException e) {
      this.tmpPath = null;
      this.serializer = null;
      throw new RuntimeException(e);
    }
  }
}
