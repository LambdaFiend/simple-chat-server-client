import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.util.*;

public class Server {

    static private final ByteBuffer buffer = ByteBuffer.allocate(16384);
    static private final Charset charset = Charset.forName("UTF8");
    static private final CharsetDecoder decoder = charset.newDecoder();
    static private HashSet<String> takenNames = new HashSet<String>();
    static private HashMap<String, HashSet<SelectionKey>> rooms = new HashMap<String, HashSet<SelectionKey>>();
    static private HashMap<String, SelectionKey> nameToKey = new HashMap<String, SelectionKey>();
    static private ArrayList<SelectionKey> keysToSendTo = new ArrayList<SelectionKey>();
    static private boolean messagesToSend;

    static public void main(String args[]) throws Exception {
        int port = Integer.parseInt(args[0]);
        try {
            ServerSocketChannel ssc = ServerSocketChannel.open();
            ssc.configureBlocking(false);
            ServerSocket ss = ssc.socket();
            InetSocketAddress isa = new InetSocketAddress(port);
            ss.bind(isa);
            Selector selector = Selector.open();
            ssc.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("Listening on port " + port);
            while (true) {
                int num = selector.select();
                if (num == 0) continue;
                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> it = keys.iterator();
                messagesToSend = false;
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    if (key.isAcceptable()) {
                        Socket s = ss.accept();
                        System.out.println( "Got connection from "+s );
                        SocketChannel sc = s.getChannel();
                        sc.configureBlocking( false );
                        sc.register( selector, SelectionKey.OP_READ );
                    } else if (key.isReadable()) {
                        SocketChannel sc = null;
                        try {
                            sc = (SocketChannel)key.channel();
                            boolean ok;
                            if (key.attachment() == null) key.attach(new ClientInfo());
                            ClientInfo ci = (ClientInfo)key.attachment();
                            ci.key = key;
                            Set<SelectionKey> sk = selector.keys();
                            ok = processInput(sc, sk, ci);
                            if (!ok) {
                                if (ci.room != null) {
                                    sendRoom("LEFT " + ci.nickname + "\n", sk, ci, false);
                                    rooms.get(ci.room).remove(ci.key);
                                    if (rooms.get(ci.room).size() == 0) rooms.remove(ci.room);
                                }
                                if (ci.nickname != null) {
                                    takenNames.remove(ci.nickname);
                                    nameToKey.remove(ci.nickname);
                                }
                                sendAll();
                                key.cancel();
                                Socket s = null;
                                try {
                                    s = sc.socket();
                                    System.out.println("Closing connection to " + s);
                                    s.close();
                                } catch(IOException ie) { System.err.println("Error closing socket " + s + ": " + ie); }
                            }
                        } catch (IOException ie) {
                            key.cancel();
                            try { sc.close(); } catch (IOException ie2) { System.out.println(ie2); }
                            System.out.println("Closed " + sc);
                        }
                    }
                }
                if (messagesToSend) { try { sendAll(); } catch (IOException ie3) { System.out.println(ie3); } }
                keys.clear();
            }
        } catch(IOException ie) { System.err.println(ie); }
    }

    static private boolean processInput(SocketChannel sc, Set<SelectionKey> sk, ClientInfo ci) throws IOException {
        buffer.clear(); sc.read(buffer); buffer.flip();
        if (buffer.limit() == 0) return false;
        String entireMessage = decoder.decode(buffer).toString(); buffer.clear();
        if (entireMessage.indexOf('\n') == -1) {
            ci.buffer.append(entireMessage);
            return true;
        }
        String[] messages = entireMessage.split("\n");
        if (messages.length > 0) {
            if (entireMessage.charAt(entireMessage.length() - 1) != '\n') {
                ci.buffer.append(messages[messages.length - 1]);
                messages[messages.length - 1] = null;
            }
            ci.buffer.append(messages[0]); messages[0] = ci.buffer.toString(); ci.buffer = new StringBuilder();
        }
        for (String message : messages) {
            if (message == null || message == "") continue; else messagesToSend = true;
            String[] splitMessage = message.split(" ");
            if (splitMessage.length == 1 && splitMessage[0].equals("/bye")) {
                send("BYE" + "\n", ci);
                return false;
            }
            if (ci.nickname == null) {
                if (splitMessage.length == 2 && splitMessage[0].equals("/nick") && !takenNames.contains(splitMessage[1])) {
                    ci.nickname = splitMessage[1];
                    takenNames.add(ci.nickname);
                    nameToKey.put(ci.nickname, ci.key);
                    send("OK" + "\n", ci);
                } else send("ERROR" + "\n", ci);
            } else if (ci.room == null) {
                if (splitMessage.length == 2) {
                    if (splitMessage[0].equals("/join")) {
                        ci.room = splitMessage[1];
                        if (rooms.containsKey(ci.room)) rooms.get(ci.room).add(ci.key); else rooms.put(ci.room, new HashSet<SelectionKey>());
                        rooms.get(ci.room).add(ci.key);
                        send("OK" + "\n", ci);
                        sendRoom("JOINED " + ci.nickname + "\n", sk, ci, false);
                    } else if (splitMessage[0].equals("/nick") && !takenNames.contains(splitMessage[1])) {
                        takenNames.remove(ci.nickname);
                        nameToKey.remove(ci.nickname);
                        ci.nickname = splitMessage[1];
                        takenNames.add(ci.nickname);
                        nameToKey.put(ci.nickname, ci.key);
                        send("OK" + "\n", ci);
                    } else send("ERROR" + "\n", ci);
                } else if (message.charAt(0) == '/' && splitMessage.length > 2 && splitMessage[0].equals("/priv")) {
                    if (nameToKey.get(splitMessage[1]) == null) send("ERROR" + "\n", ci); else {
                        send("PRIVATE " + ci.nickname + " " + message.split(" ", 3)[2] + "\n", (ClientInfo)(nameToKey.get(splitMessage[1]).attachment()));
                        send("OK" + "\n", ci);
                    }
                } else send("ERROR" + "\n", ci);
            } else {
                if (message.length() > 1 && message.charAt(0) == '/' && message.charAt(1) == '/') {
                    sendRoom("MESSAGE " + ci.nickname + " " + message.substring(1) + "\n", sk, ci, true);
                } else if (message.charAt(0) == '/' && splitMessage.length == 2) {
                    if (splitMessage[0].equals("/join")) {
                        ci.room = splitMessage[1];
                        sendRoom("LEFT " + ci.nickname + "\n", sk, ci, false);
                        if (rooms.containsKey(ci.room)) rooms.get(ci.room).add(ci.key); else rooms.put(ci.room, new HashSet<SelectionKey>());
                        rooms.get(ci.room).add(ci.key);
                        send("OK" + "\n", ci);
                        sendRoom("JOINED " + ci.nickname + "\n", sk, ci, false);
                    } else if (splitMessage[0].equals("/nick") && !takenNames.contains(splitMessage[1])) {
                        sendRoom("NEWNICK " + ci.nickname + " " + splitMessage[1] + "\n", sk, ci, false);
                        takenNames.remove(ci.nickname);
                        nameToKey.remove(ci.nickname);
                        ci.nickname = splitMessage[1];
                        takenNames.add(ci.nickname);
                        nameToKey.put(ci.nickname, ci.key);
                        send("OK" + "\n", ci);
                    } else send("ERROR" + "\n", ci);
                } else if (message.charAt(0) == '/' && splitMessage.length == 1) {
                    if (splitMessage[0].equals("/leave")) {
                        sendRoom("LEFT " + ci.nickname + "\n", sk, ci, false);
                        rooms.get(ci.room).remove(ci.key);
                        if (rooms.get(ci.room).size() == 0) rooms.remove(ci.room);
                        ci.room = null;
                        send("OK" + "\n", ci);
                    } else send("ERROR" + "\n", ci);
                } else if (message.charAt(0) == '/' && splitMessage.length > 2 && splitMessage[0].equals("/priv")) {
                    if (nameToKey.get(splitMessage[1]) == null) send("ERROR" + "\n", ci); else {
                        send("PRIVATE " + ci.nickname + " " + message.split(" ", 3)[2] + "\n", (ClientInfo)(nameToKey.get(splitMessage[1]).attachment()));
                        send("OK" + "\n", ci);
                    }
                } else sendRoom("MESSAGE " + ci.nickname + " " + message + "\n", sk, ci, true);
            }
        }
        return true;
    }

    static private void send(String message, ClientInfo ci) throws IOException { ci.messages.append(message); keysToSendTo.add(ci.key); }

    static private void sendRoom(String message, Set<SelectionKey> keys, ClientInfo ci, boolean sendSelf) throws IOException { for (SelectionKey key : rooms.get(ci.room)) if (key.isValid() && !key.isAcceptable() && (sendSelf || key != ci.key)) { ((ClientInfo)key.attachment()).messages.append(message); keysToSendTo.add(key); } }

    static private boolean sendFinal(String message, SocketChannel sc) throws IOException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8); ByteBuffer messageBuffer = ByteBuffer.wrap(bytes);
        try { while (messageBuffer.hasRemaining()) sc.write(messageBuffer); } catch (IOException ie) { System.out.println(ie); return false; }
        return true;
    }

    static private boolean sendAll() throws IOException {
         for (SelectionKey key : keysToSendTo) {
             ClientInfo ci = (ClientInfo)key.attachment(); SocketChannel sc = (SocketChannel)key.channel(); StringBuilder messages = ci.messages; ci.messages = new StringBuilder(); int counter = 0;
             while (messages.length() > counter * (16384 / 8)) if (!sendFinal(messages.substring(counter * (16384 / 8), Math.min((counter + 1) * (16384 / 8), messages.length())), sc)) return false; else counter++;
         }
         keysToSendTo = new ArrayList<SelectionKey>(); messagesToSend = false;
         return true;
    }

}

public class ClientInfo {
    public SelectionKey key = null;
    public String nickname = null;
    public StringBuilder buffer = new StringBuilder();
    public String room = null;
    public StringBuilder messages = new StringBuilder();
}
