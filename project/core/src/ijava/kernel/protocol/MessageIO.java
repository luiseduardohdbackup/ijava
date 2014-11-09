// SocketHandler.java
//

package ijava.kernel.protocol;

import java.util.*;
import com.fasterxml.jackson.jr.ob.*;
import org.zeromq.ZMQ.*;

/**
 * Implements message reading and writing per the kernel communication protocol.
 */
public final class MessageIO {

  private final static String DELIMITER = "<IDS|MSG>";

  private MessageIO() {
  }

  /**
   * Reads an incoming message from the specified socket.
   * @param socket the socket to read from.
   * @return the resulting Message instance.
   */
  public static Message readMessage(Socket socket) {
    try {
      String identity = null;
      for (String id = socket.recvStr(); !id.equals(MessageIO.DELIMITER); id = socket.recvStr()) {
        if (identity == null) {
          identity = id;
        }

        // TODO: When are there multiple identities?
      }

      String hmac = socket.recvStr();
      String headerJson = socket.recvStr();
      String parentHeaderJson = socket.recvStr();
      String metadataJson = socket.recvStr();
      String contentJson = socket.recvStr();

      // TODO: Verify HMAC

      Map<String, Object> header = JSON.std.mapFrom(headerJson);
      Map<String, Object> parentHeader = JSON.std.mapFrom(parentHeaderJson);
      Map<String, Object> metadata = JSON.std.mapFrom(metadataJson);
      Map<String, Object> content = JSON.std.mapFrom(contentJson);

      return Message.createMessage(identity, header, parentHeader, metadata, content);
    }
    catch (Exception e) {
      // TODO: Logging
      e.printStackTrace();

      return null;
    }
  }

  /**
   * Writes an out-going message to the specified socket.
   * @param socket the socket to write to.
   * @param message the message to send.
   */
  public static void writeMessage(Socket socket, Message message) {
    try {
      String identity = message.getIdentity();
      String headerJson = JSON.std.asString(message.getHeader());
      String parentHeaderJson = JSON.std.asString(message.getParentHeader());
      String metadataJson = JSON.std.asString(message.getMetadata());
      String contentJson = JSON.std.asString(message.getContent());

      // TODO: Compute HMAC
      String hmac = "";

      if (identity != null) {
        socket.sendMore(identity);
      }

      socket.sendMore(MessageIO.DELIMITER);
      socket.sendMore(hmac);
      socket.sendMore(headerJson);
      socket.sendMore(parentHeaderJson);
      socket.sendMore(metadataJson);
      socket.send(contentJson);
    }
    catch (Exception e) {
      // TODO: Logging
      e.printStackTrace();
    }
  }
}
