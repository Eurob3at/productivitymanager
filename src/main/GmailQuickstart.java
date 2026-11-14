import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/* class to demonstrate listing individual Gmail messages */
public class GmailQuickstart {
  /**
   * Application name.
   */
  private static final String APPLICATION_NAME = "Gmail API Java Quickstart";
  /**
   * Global instance of the JSON factory.
   */
  private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
  /**
   * Directory to store authorization tokens for this application.
   */
  private static final String TOKENS_DIRECTORY_PATH = "tokens";

  /**
   * If modifying these scopes, delete the previously saved tokens/ folder to authorize again.
   */
  private static final List<String> SCOPES = Collections.singletonList(GmailScopes.GMAIL_READONLY);
  private static final String CREDENTIALS_FILE_PATH = "/credentials.json";
  private static final String OUTPUT_FILE_PATH = "src/main/resources.txt";
  private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

  /**
   * Creates an authorized Credential object.
   *
   * @param HTTP_TRANSPORT The network HTTP Transport.
   * @return An authorized Credential object.
   * @throws IOException If the credentials.json file cannot be found.
   */
  private static Credential getCredentials(final NetHttpTransport HTTP_TRANSPORT)
      throws IOException {
    // Load client secrets.
    InputStream in = GmailQuickstart.class.getResourceAsStream(CREDENTIALS_FILE_PATH);
    if (in == null) {
      throw new FileNotFoundException("Resource not found: " + CREDENTIALS_FILE_PATH);
    }
    GoogleClientSecrets clientSecrets =
        GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

    // Build flow and trigger user authorization request.
    GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
        HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
        .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
        .setAccessType("offline")
        .build();
    LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
    return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
  }

  public static void main(String... args) throws IOException, GeneralSecurityException {
    // Build a new authorized API client service.
    final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
    Gmail service = new Gmail.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
        .setApplicationName(APPLICATION_NAME)
        .build();

    // Export the 50 newest messages in the user's mailbox.
    String user = "me";
    StringBuilder output = new StringBuilder();
    int exportedCount = 0;
    ListMessagesResponse response =
        service.users().messages().list(user).setMaxResults(50L).execute();
    List<Message> messages = response.getMessages();
    if (messages != null) {
      for (Message message : messages) {
        Message details = service.users().messages().get(user, message.getId())
            .setFormat("full")
            .execute();
        if (details.getInternalDate() == null) {
          throw new IOException("Message is missing its internal date: " + message.getId());
        }
        ZonedDateTime receivedAt =
            Instant.ofEpochMilli(details.getInternalDate()).atZone(ZoneId.systemDefault());
        if (exportedCount > 0) {
          output.append("\n\n================ EMAIL ================\n\n");
        }
        output.append(extractSender(getHeader(details.getPayload(), "From"))).append('\n')
            .append(getHeader(details.getPayload(), "Subject")).append('\n')
            .append(receivedAt.toLocalDate()).append(' ')
            .append(TIME_FORMATTER.format(receivedAt)).append('\n')
            .append(details.getId()).append('\n')
            .append(getBodyText(details.getPayload()));
        exportedCount++;
      }
    }

    Files.write(
        Paths.get(OUTPUT_FILE_PATH),
        output.toString().getBytes(StandardCharsets.UTF_8));
    System.out.println("Saved " + exportedCount + " messages to " + OUTPUT_FILE_PATH);
    if (exportedCount == 0) {
      System.out.println("No messages found.");
    }
  }

  private static String extractSender(String from) {
    int start = from.lastIndexOf('<');
    int end = from.indexOf('>', start + 1);
    if (start >= 0 && end > start) {
      return from.substring(start + 1, end).trim();
    }
    return from.trim();
  }

  private static String getBodyText(MessagePart payload) {
    String text = findBodyPart(payload, "text/plain");
    if (text.isEmpty()) {
      text = findBodyPart(payload, "text/html");
    }
    return text;
  }

  private static String findBodyPart(MessagePart part, String mimeType) {
    if (part == null) {
      return "";
    }
    if (mimeType.equalsIgnoreCase(part.getMimeType())
        && part.getBody() != null
        && part.getBody().getData() != null) {
      return new String(
          Base64.getUrlDecoder().decode(part.getBody().getData()), StandardCharsets.UTF_8);
    }
    if (part.getParts() != null) {
      for (MessagePart child : part.getParts()) {
        String text = findBodyPart(child, mimeType);
        if (!text.isEmpty()) {
          return text;
        }
      }
    }
    return "";
  }

  private static String getHeader(MessagePart payload, String name) {
    if (payload != null && payload.getHeaders() != null) {
      for (MessagePartHeader header : payload.getHeaders()) {
        if (name.equalsIgnoreCase(header.getName())) {
          return header.getValue();
        }
      }
    }
    return "";
  }
}