import java.io.*;
import java.net.*;
import java.util.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import java.nio.charset.*;

public class ChatClient {

    JFrame frame = new JFrame("Chat Client");
    private JTextField chatBox = new JTextField();
    private JTextArea chatArea = new JTextArea();
    Socket clientSocket;
    BufferedReader inFromServer;
    DataOutputStream outToServer;
    Set<String> commands = Set.of("/nick", "/join", "/leave", "/bye", "/priv");

    public void printMessage(final String message) { chatArea.append(message + "\n"); chatArea.setCaretPosition(chatArea.getDocument().getLength()); }

    public ChatClient(String server, int port) throws IOException {
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());
        panel.add(chatBox);
        frame.setLayout(new BorderLayout());
        frame.add(panel, BorderLayout.SOUTH);
        frame.add(new JScrollPane(chatArea), BorderLayout.CENTER);
        frame.setSize(500, 300);
        frame.setVisible(true);
        chatArea.setEditable(false);
        chatBox.setEditable(true);
        chatBox.addActionListener(new ActionListener() { @Override public void actionPerformed(ActionEvent e) { try { newMessage(chatBox.getText()); } catch (IOException ex) { } finally { chatBox.setText(""); } } });
        frame.addWindowListener(new WindowAdapter() { public void windowOpened(WindowEvent e) { chatBox.requestFocusInWindow(); } });
        clientSocket = new Socket(server, port);
        inFromServer = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
        outToServer = new DataOutputStream(clientSocket.getOutputStream());
    }

    public void newMessage(String message) throws IOException {
        if (message.charAt(0) == '/' && !isCommand(message)) message = '/' + message;
        outToServer.write((message + "\n").getBytes()); outToServer.flush();
    }

    public void run() throws IOException {
        Thread thread = new Thread() { @Override
            public void run() {
                try {
                    while (true) {
                        String message = inFromServer.readLine(); String[] parts = message.split(" ", 3); String action = parts[0];
                        switch (action) {
                            case "OK": printMessage("The command was successful."); break;
                            case "ERROR": printMessage("The command failed."); break;
                            case "BYE": printMessage("Bye bye!"); clientSocket.close(); return;
                            case "MESSAGE": printMessage(parts[1] + ": " + parts[2]); break;
                            case "NEWNICK": printMessage(parts[1] + " changed his name to " + parts[2] + "."); break;
                            case "JOINED": printMessage(parts[1] + " joined the room."); break;
                            case "LEFT": printMessage(parts[1] + " left the room."); break;
                            case "PRIVATE": printMessage("Private message from " + parts[1] + ": " + parts[2]); break;
                            default: printMessage(message); break;
                        }
                    }
                } catch (IOException ie) { System.out.println(ie); }
            }
        };
        thread.start();
    }

    public boolean isCommand(String message) { return commands.contains(message.split(" ")[0]); }

    public static void main(String[] args) throws IOException { ChatClient client = new ChatClient(args[0], Integer.parseInt(args[1])); client.run(); }

}
