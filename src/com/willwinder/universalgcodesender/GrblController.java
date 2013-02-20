/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.willwinder.universalgcodesender;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedList;
import javax.vecmath.Point3d;

/**
 *
 * @author Owen
 */
public class GrblController implements SerialCommunicatorListener {
    private SerialCommunicator comm;

    // Grbl state
    private double grblVersion;         // The 0.8 in 'Grbl 0.8c'
    private String grblVersionLetter;   // The c in 'Grbl 0.8c'
    private Boolean isReady = false;    // Not ready until version is received.
    
    // TODO: Make a separate class for the polling thread?
    // Polling state
    private Boolean outstandingPolls = false;
    
    // Outside influence
    private Integer speedOverride = -1;
    
    // Grbl status members.
    private CommUtils.Capabilities positionMode = null;
    private Boolean realTimeCapable = false;
    private String grblState;
    private Point3d machineLocation;
    private Point3d workLocation;
    
    // Added value
    private Boolean isStreaming = false;
    private long streamStart = 0;
    private long streamStop = 0;
    private int numCommands = 0;
    private File gcodeFile;
    private LinkedList<GcodeCommand> activeCommandList;  // Currently running commands
    
    // Structures for organizing all streaming commands.
    private LinkedList<GcodeCommand> commandQueue;          // preparing for send
    private LinkedList<GcodeCommand> outgoingQueue;         // waiting to be sent
    private LinkedList<GcodeCommand> awaitingResponseQueue; // waiting for response
    private LinkedList<GcodeCommand> completedCommandList;  // received response
    private LinkedList<GcodeCommand> errorCommandList;      // error in response
    
    // Listeners
    private ArrayList<ControllerListener> listeners;
    
    // Helper objects
    private GcodeCommandCreator commandCreator;
    
    public GrblController() {
        this.comm = new SerialCommunicator();
        
        this.activeCommandList = new LinkedList<GcodeCommand>();
        
        this.commandQueue = new LinkedList<GcodeCommand>();
        this.outgoingQueue = new LinkedList<GcodeCommand>();
        this.awaitingResponseQueue = new LinkedList<GcodeCommand>();
        this.completedCommandList = new LinkedList<GcodeCommand>();
        this.errorCommandList = new LinkedList<GcodeCommand>();
        
        // Register comm listeners
        this.comm.setListenAll(this);
        
        this.listeners = new ArrayList<ControllerListener>();
        
        this.commandCreator = new GcodeCommandCreator();
    }
    
    /**
     * Overrides the feed rate in gcode commands. Disable by setting to -1.
     */
    public void setSpeedOverride(int override) {
        this.speedOverride = override;
    }
    
    public Boolean openCommPort(String port, int portRate) throws Exception {
        // No point in checking response, it throws an exception on errors.
        this.comm.openCommPort(port, portRate);
        
        this.messageForConsole("**** Connected to "
                                        + port
                                        + " @ "
                                        + portRate
                                        + " baud ****\n");

        return true;
    }
    
    public Boolean closeCommPort() {
        this.messageForConsole("**** Connection closed ****\n");
        this.comm.closeCommPort();
        return true;
    }
    
    //// File send metadata ////
    
    public Boolean isStreamingFile() {
        return this.isStreaming;
    }
    
    public long getSendDuration() {
        if (this.isStreaming == false) {
            return this.streamStop - this.streamStart;
        } else {
            return System.currentTimeMillis() - this.streamStart;
        }
    }
    
    public int rowsInSend() {
        return this.numCommands;
        // Because of discarded commands the value had to be cached early.
        //return  this.outgoingQueue.size() +
        //        this.awaitingResponseQueue.size() +
        //        this.completedCommandList.size();
    }
    
    public int rowsSent() {
        return this.numCommands - this.outgoingQueue.size();
        
        //return this.completedCommandList.size() + this.awaitingResponseQueue.size();
    }
    
    public int rowsRemaining() {
        return this.outgoingQueue.size();
    }
    
    /**
     * Creates a gcode command and queues it for send immediately.
     * Note: this is the only place where a string is sent to the comm.
     */
    public void queueStringForComm(String str) throws Exception {
        GcodeCommand command = this.commandCreator.createCommand(str);
        this.outgoingQueue.add(command);
        this.sendStringToComm(command.getCommandString());
    }
    
    private void sendStringToComm(String command) throws Exception {
        this.comm.queueStringForComm(command+"\n");
    }
    
    public void isReadyToStreamFile() throws Exception {
        this.comm.isReadyToStreamFile();
        if (this.isReady == false) {
            throw new Exception("Grbl has not finished booting.");
        }
    }
    
    /**
     * Appends command string to a queue awaiting to be sent.
     */
    public void appendGcodeCommand(String commandString) {
        GcodeCommand command = this.commandCreator.createCommand(commandString);
        this.commandQueue.add(command);

        this.commandQueued(command);
    }
    
    /**
     * Appends file of commands to a queue awaiting to be sent. Exception is
     * thrown on file IO errors.
     */
    public void appendGcodeFile(File file) throws IOException {
        this.gcodeFile = file;
        ArrayList<String> linesInFile = 
                VisualizerUtils.readFiletoArrayList(this.gcodeFile.getAbsolutePath());

        for (String line : linesInFile) {
            this.appendGcodeCommand(line);
        }
    }
    
    /**
     * Send all queued commands to comm port.
     */
    public void beginStreaming() throws Exception {
        if (this.outgoingQueue.size() != 0 || this.awaitingResponseQueue.size() != 0) {
            throw new Exception("Cannot begin streaming until there are no outstanding commands.");
        }
        
        this.isStreaming = true;
        this.streamStop = 0;
        this.streamStart = System.currentTimeMillis();
        this.numCommands = this.commandQueue.size();
        
        try {
            // Send all queued commands and wait for a response.
            GcodeCommand command;
            String processed;
            while (this.commandQueue.size() > 0) {
                command = this.commandQueue.remove();
                
                // TODO: Expand this to handle canned cycles (Issue#49)
                processed = this.preprocessCommand(command.getCommandString());
                
                // Don't send zero length commands.
                if (processed.trim().equals("")) {
                    this.messageForConsole("Skipping command #" + command.getCommandNumber() + "\n");
                    command.setResponse("<skipped by application>");
                    this.commandComplete(command);
                    // For the listeners...
                    dispatchCommandSent(listeners, command);
                } else {
                    this.outgoingQueue.add(command);
                    this.sendStringToComm(processed);
                }
            }
        } catch(Exception e) {
            this.isStreaming = false;
            this.streamStart = 0;
            throw e;
        }
    }
    
    public void pauseStreaming() throws IOException {
        this.messageForConsole("\n**** Pausing file transfer. ****\n\n");
        this.comm.pauseSend();
        if (this.realTimeCapable) {
            this.comm.sendByteImmediately(CommUtils.GRBL_PAUSE_COMMAND);
        }
    }
    
    public void resumeStreaming() throws IOException {
        this.messageForConsole("\n**** Resuming file transfer. ****\n\n");
        this.comm.resumeSend();
        if (this.realTimeCapable) {
            this.comm.sendByteImmediately(CommUtils.GRBL_RESUME_COMMAND);
        }
    }
    
    public void cancelSend() {
        this.messageForConsole("\n**** Canceling file transfer. ****\n\n");
        
        this.flushSendQueues();
        this.comm.cancelSend();
    }
    
    private void flushSendQueues() {
        this.commandQueue.clear();
        this.outgoingQueue.clear();
        this.awaitingResponseQueue.clear();
        this.completedCommandList.clear();
        this.errorCommandList.clear();
    }

    private void printStateOfQueues() {
        System.out.println("command queue size = " + this.commandQueue.size());
        System.out.println("outgoing queue size = " + this.outgoingQueue.size());
        System.out.println("awaiting response queue size = " + this.awaitingResponseQueue.size());
        System.out.println("completed command list size = " + this.completedCommandList.size());
        System.out.println("error command list size = " + this.errorCommandList.size());
        System.out.println("============");
        
    }
    
    // No longer a listener event
    public void commandQueued(GcodeCommand command) {
        dispatchCommandQueued(listeners, command);
    }

    public void fileStreamComplete(String filename, boolean success) {
        this.messageForConsole("\n**** Finished sending file. ****\n\n");
        this.streamStop = System.currentTimeMillis();
        dispatchStreamComplete(listeners, filename, success);        
    }
        
    public String preprocessCommand(String command) {
        String newCommand = command;

        // Remove comments from command.
        newCommand = CommUtils.removeComment(newCommand);

        // Check for comment if length changed while preprocessing.
        if (command.length() != newCommand.length()) {
            String comment = CommUtils.parseComment(command);
            dispatchCommandCommment(listeners, CommUtils.parseComment(command));
        }
        
        // Override feed speed
        if (this.speedOverride > 0) {
            newCommand = CommUtils.overrideSpeed(command, this.speedOverride);
        }

        // Return the post processed command.
        return newCommand;
    }
    
    // TODO: SerialCommunicator shouldn't know what a capability is.
    @Override
    public void capabilitiesListener(CommUtils.Capabilities capability) {
        
        if (capability == CommUtils.Capabilities.POSITION_C) {
            System.out.println("Found position C capability");
            this.positionMode = capability;
        } else if (capability == CommUtils.Capabilities.REAL_TIME) {
            this.realTimeCapable = true;
            System.out.println("Found real time capability");
        }
        
    }
     
    @Override
    public void commandSent(GcodeCommand command) {
        GcodeCommand c = this.outgoingQueue.remove();
        c.setSent(true);
        
        this.awaitingResponseQueue.add(c);
        
        dispatchCommandSent(listeners, c);
    }
    
    @Override
    public void commandComment(String comment) {
        dispatchCommandCommment(listeners, comment);
    }
    
    @Override
    public void commandComplete(GcodeCommand command) {
        GcodeCommand c = command;
        
        // If the command wasn't sent, it was discarted and should be ignored
        // from the remaining queues.
        if (command.isSent()) {
            c = this.awaitingResponseQueue.remove();
            c.setResponse(command.getResponse());
            this.completedCommandList.add(c);
        }
        
        dispatchCommandComplete(listeners, c);
        
        if (this.isStreamingFile() &&
                this.awaitingResponseQueue.size() == 0 &&
                this.outgoingQueue.size() == 0 &&
                this.commandQueue.size() == 0) {
            this.fileStreamComplete(this.gcodeFile.getName(), true);
        }
    }

    @Override
    public void messageForConsole(String msg) {
        dispatchConsoleMessage(listeners, msg, Boolean.FALSE);
    }
    
    @Override
    public void verboseMessageForConsole(String msg) {
        dispatchConsoleMessage(listeners, msg, Boolean.TRUE);
    }
    
    public void handlePositionString(String string) {
        if (this.positionMode != null) {
            this.grblState = GrblUtils.getStatusFromPositionString(string, this.positionMode);
            this.machineLocation = GrblUtils.getMachinePositionFromPositionString(string, this.positionMode);
            this.workLocation = GrblUtils.getWorkPositionFromPositionString(string, this.positionMode);
         
            dispatchStatusString(listeners, this.grblState, this.machineLocation, this.workLocation);
        }
    }

    @Override
    public void rawResponseListener(String response) {        
        /*
        // Check if was 'ok' or 'error'.
        if (GcodeCommand.isOkErrorResponse(response)) {
            
            // All Ok/Error messages go to console
            this.sendMessageToConsoleListener(response + "\n");

            // Pop the front of the active list.
            GcodeCommand command = this.activeCommandList.pop();

            command.setResponse(response);

            ListenerUtils.dispatchListenerEvents(ListenerUtils.COMMAND_COMPLETE, 
                    this.commandCompleteListeners, command);

            if (this.sendPaused == false) {
                this.streamCommands();
            }
        }
        */
        if (GrblUtils.isGrblVersionString(response)) {
            // Version string goes to console
            this.messageForConsole(response + "\n");
            
            this.grblVersion = GrblUtils.getVersionDouble(response);
            this.grblVersionLetter = GrblUtils.getVersionLetter(response);
            this.isReady = true;
            
            this.realTimeCapable = GrblUtils.isRealTimeCapable(this.grblVersion);
            
            this.positionMode = GrblUtils.getGrblPositionCapabilities(this.grblVersion, this.grblVersionLetter);
            if (this.positionMode != null) {
                // Start sending '?' commands.
                //this.beginPollingPosition();
            }
            
            System.out.println("Grbl version = " + this.grblVersion + this.grblVersionLetter);
            System.out.println("Real time mode = " + this.realTimeCapable);
        }
        
        else if (GrblUtils.isGrblPositionString(response)) {
            this.outstandingPolls = false;

            // Position string goes to verbose console
            dispatchConsoleMessage(listeners, response + "\n", true);
            
            this.handlePositionString(response);
        }
        
        else {
            // Display any unhandled messages
            this.messageForConsole(response + "\n");
        }
    }

    /**
     * Listener management.
     */
    void addListener(ControllerListener cl) {
        this.listeners.add(cl);
    }

    //  void statusStringListener(String state, Point3d machineCoord, Point3d workCoord);
    static private void dispatchStatusString(ArrayList<ControllerListener> clList, String state, Point3d work, Point3d machine) {
        if (clList != null) {
            for (ControllerListener c : clList) {
                c.statusStringListener(state, machine, work);
            }
        }
    }
    
    //  void messageForConsole(String msg, Boolean verbose);
    static private void dispatchConsoleMessage(ArrayList<ControllerListener> clList, String message, Boolean verbose) {
        if (clList != null) {
            for (ControllerListener c : clList) {
                c.messageForConsole(message, verbose);
            }
        }
    }
    
    //  void fileStreamComplete(String filename, boolean success);
    static private void dispatchStreamComplete(ArrayList<ControllerListener> clList, String filename, Boolean success) {
        if (clList != null) {
            for (ControllerListener c : clList) {
                c.fileStreamComplete(filename, success);
            }
        }
    }
    
    //  void commandComplete(GcodeCommand command);
    static private void dispatchCommandQueued(ArrayList<ControllerListener> clList, GcodeCommand command) {
        if (clList != null) {
            for (ControllerListener c : clList) {
                c.commandQueued(command);
            }
        }
    }
    
    //  void commandSent(GcodeCommand command);
    static private void dispatchCommandSent(ArrayList<ControllerListener> clList, GcodeCommand command) {
        if (clList != null) {
            for (ControllerListener c : clList) {
                c.commandSent(command);
            }
        }
    }
    
    //  void commandComplete(GcodeCommand command);
    static private void dispatchCommandComplete(ArrayList<ControllerListener> clList, GcodeCommand command) {
        if (clList != null) {
            for (ControllerListener c : clList) {
                c.commandComplete(command);
            }
        }
    }
    
    //  void commandComment(String comment);
    static private void dispatchCommandCommment(ArrayList<ControllerListener> clList, String comment) {
        if (clList != null) {
            for (ControllerListener c : clList) {
                c.commandComment(comment);
            }
        }
    }
}