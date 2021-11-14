package no.ntnu.datakomm.chat;

import java.io.*;
import java.net.*;
import java.util.LinkedList;
import java.util.List;

public class TCPClient {
    private PrintWriter toServer;
    private BufferedReader fromServer;
    private Socket connection;

    private String lastError = null;
    private final List<ChatListener> listeners = new LinkedList<>();

    /**
     * Connect to a chat server.
     *
     * @param host host name or IP address of the chat server
     * @param port TCP port of the chat server
     * @return True on success, false otherwise
     */
    public boolean connect(String host, int port) {

        // Step 1: implement this method

        boolean connectionStatus = false;
        try {
            connection = new Socket(host,port);
            InputStream inputStream = connection.getInputStream();
            OutputStream outputStream = connection.getOutputStream();
            toServer = new PrintWriter(outputStream,true);
            fromServer = new BufferedReader(new InputStreamReader(inputStream));
            connectionStatus = true;
        } catch (UnknownHostException uhe) {
            lastError = "The host name is unknown";
        } catch (ConnectException ce) {
            lastError = "No chat server found";
        } catch (IOException e) {
            lastError = "IO error";
        }
        return connectionStatus;

    }

    /**
     * Close the socket. This method must be synchronized, because several
     * threads may try to call it. For example: When "Disconnect" button is
     * pressed in the GUI thread, the connection will get closed. Meanwhile, the
     * background thread trying to read server's response will get error in the
     * input stream and may try to call this method when the socket is already
     * in the process of being closed. with "synchronized" keyword we make sure
     * that no two threads call this method in parallel.
     */
    public synchronized void disconnect() {
        //  Step 4: implement this method
        if (isConnectionActive()) {
            System.out.println("Disconnecting...");
            try {
                toServer.close();
                fromServer.close();
                connection.close();
            } catch (IOException e) {
                lastError = e.getMessage();
                System.out.println("Error while closing connection: " + lastError);
            }
        }
        connection = null;
    }

    /**
     * @return true if the connection is active (opened), false if not.
     */
    public boolean isConnectionActive() {
        return connection != null;
    }

    /**
     * Send a command to server.
     *
     * @param cmd A command. It should include the command word and optional attributes, according to the protocol.
     * @return true on success, false otherwise
     */
    private boolean sendCommand(String cmd) {
        //Step 2: Implement this method
        if (isConnectionActive()) {
            System.out.println(">>> " + cmd);
            toServer.println(cmd);
            return true;
        } else {
            System.out.println("No connection!");
            return false;
        }
    }

    /**
     * Send a public message to all the recipients.
     *
     * @param message Message to send
     * @return true if message sent, false on error
     */
    public boolean sendPublicMessage(String message) {
        //Step 2: implement this method
        return sendCommand("msg " + message);
    }

    /**
     * Send a login request to the chat server.
     *
     * @param username Username to use
     */
    public void tryLogin(String username) {
        //Step 3: implement this method
        sendCommand("login " + username);
    }

    /**
     * Send a request for latest user list to the server. To get the new users,
     * clear your current user list and use events in the listener.
     */
    public void refreshUserList() {
        //  Step 5: implement this method
        sendCommand("users");
    }

    /**
     * Send a private message to a single recipient.
     *
     * @param recipient username of the chat user who should receive the message
     * @param message   Message to send
     * @return true if message sent, false on error
     */
    public boolean sendPrivateMessage(String recipient, String message) {
        // Step 6: Implement this method
        return sendCommand("privmsg " + recipient + " " + message) ;
    }

    /**
     * Send a request for the list of commands that server supports.
     */
    public void askSupportedCommands() {
        //  Step 8: Implement this method
        sendCommand("help");
    }


    /**
     * Wait for chat server's response
     *
     * @return one line of text (one command) received from the server
     */
    private String waitServerResponse() {
        //  Step 3: Implement this method
        //  Step 4: If you get I/O Exception or null from the stream, it means that something has gone wrong
        String output = null;
        try {
            output = fromServer.readLine();
            if (output != null) {
                System.out.println("<<< " + output);
            } else {
                disconnect();
            }
        } catch (IOException ex) {
            System.out.println("Err while reading from server, socket closed");
            lastError = "Server closed socket";
            disconnect();
            onDisconnect();
        }
        return output;
    }

    /**
     * Get the last error message
     *
     * @return Error message or "" if there has been no error
     */
    public String getLastError() {
        if (lastError != null) {
            return lastError;
        } else {
            return "";
        }
    }

    /**
     * Start listening for incoming commands from the server in a new CPU thread.
     */
    public void startListenThread() {
        // Call parseIncomingCommands() in the new thread.
        Thread t = new Thread(this::parseIncomingCommands);
        t.start();
    }

    /**
     * Read incoming messages one by one, generate events for the listeners. A loop that runs until
     * the connection is closed.
     */
    private void parseIncomingCommands() {
        while (isConnectionActive()) {
            String line = waitServerResponse();
            if (line != null && line.length() > 0) {

                String[] parts = line.split(" ", 2);
                String cmd = parts[0];
                String params;
                if (parts.length == 2) {
                    params = parts[1];
                } else {
                    params = "";
                }

                switch (cmd) {
                    case "loginok":
                        onLoginResult(true, null);
                        break;

                    case "loginerr":
                        onLoginResult(false, params);
                        break;

                    case "msg":
                        String[] messageToBeSent = line.split(" ", 2);
                        String sender = messageToBeSent[0];
                        String message;
                        if (messageToBeSent.length == 2) {
                            message = messageToBeSent[1];
                        } else {
                            message = "";
                        }
                        onMsgReceived(false, sender, message);
                        break;

                    case "privmsg":
                        parts = params.split(" ", 2);
                        if (parts.length == 2) {
                            String senderr = parts[0];
                            String msg = parts[1];
                            onMsgReceived(true, senderr, msg);
                        }
                        break;

                    case "msgerr":
                        onMsgError(params);
                        break;

                    case "cmderr":
                        onCmdError(params);
                        break;

                    case "users":
                        onUsersList(params.split(" "));
                        break;

                    case "supported":
                        onSupported(params.split(" "));
                        break;
                }
            }
        }
    }


    /**
     * Register a new listener for events (login result, incoming message, etc)
     *
     * @param listener
     */
    public void addListener(ChatListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Unregister an event listener
     *
     * @param listener
     */
    public void removeListener(ChatListener listener) {
        listeners.remove(listener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////////////////////
    // The following methods are all event-notificators - notify all the listeners about a specific event.
    // By "event" here we mean "information received from the chat server".
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Notify listeners that login operation is complete (either with success or
     * failure)
     *
     * @param success When true, login successful. When false, it failed
     * @param errMsg  Error message if any
     */
    private void onLoginResult(boolean success, String errMsg) {
        for (ChatListener l : listeners) {
            l.onLoginResult(success, errMsg);
        }
    }

    /**
     * Notify listeners that socket was closed by the remote end (server or
     * Internet error)
     */
    private void onDisconnect() {
        //Step 4: Implement this method
        for (ChatListener l : listeners) {
            l.onDisconnect();
        }
    }

    /**
     * Notify listeners that server sent us a list of currently connected users
     *
     * @param users List with usernames
     */
    private void onUsersList(String[] users) {
        //  Step 5: Implement this method
        for (ChatListener chatListener : listeners) {
            chatListener.onUserList(users);
        }
    }

    /**
     * Notify listeners that a message is received from the server
     *
     * @param priv   When true, this is a private message
     * @param sender Username of the sender
     * @param text   Message text
     */
    private void onMsgReceived(boolean priv, String sender, String text) {
        //  Step 7: Implement this method
            TextMessage msg = new TextMessage(sender, priv, text);
            for (ChatListener l : listeners) {
                l.onMessageReceived(msg);
            }
    }

    /**
     * Notify listeners that our message was not delivered
     *
     * @param errMsg Error description returned by the server
     */
    private void onMsgError(String errMsg) {
        //  Step 7: Implement this method
        for (ChatListener chatlistener : listeners) {
            chatlistener.onMessageError(errMsg);
        }
    }

    /**
     * Notify listeners that command was not understood by the server.
     *
     * @param errMsg Error message
     */
    private void onCmdError(String errMsg) {
        //  Step 7: Implement this method
        for (ChatListener l : listeners) {
            l.onCommandError(errMsg);
        }
    }

    /**
     * Notify listeners that a help response (supported commands) was received
     * from the server
     *
     * @param commands Commands supported by the server
     */
    private void onSupported(String[] commands) {
        //  Step 8: Implement this method
        for (ChatListener l : listeners) {
            l.onSupportedCommands(commands);
        }
    }
}
