import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls upcoming assignments and calendar events from Canvas and writes them to a JSON file
 * in the same shape as extraction.py:
 *
 *   { "events": [ { "title": ..., "date": ..., "location": ..., "description": ... } ] }
 *
 * Setup (environment variables):
 *   CANVAS_TOKEN     - personal access token (Canvas > Account > Settings > New Access Token)
 *   CANVAS_BASE_URL  - your school's Canvas address, e.g. https://canvas.umass.edu
 *
 * Usage:  ./gradlew runCanvas            (writes canvas_events.json)
 *         ./gradlew runCanvas --args="other_name.json"
 */
public class CanvasFetcher {

    // ---------- Settings ----------
    private static final String BASE_URL = envOr("CANVAS_BASE_URL", "https://canvas.umass.edu");
    private static final String TOKEN = System.getenv("CANVAS_TOKEN");
    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final int DAYS_AHEAD = 60;            // how far into the future to look
    private static final int MAX_DESCRIPTION_CHARS = 500; // Canvas descriptions can be very long

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE_ONLY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    // ---------- Output format (matches extraction.py) ----------
    static class Event {
        String title;
        String date;        // "YYYY-MM-DD HH:MM" (local time), or "YYYY-MM-DD" for all-day events
        String location;    // null if none
        String description;

        Event(String title, String date, String location, String description) {
            this.title = title;
            this.date = date;
            this.location = location;
            this.description = description;
        }
    }

    static class EventList {
        List<Event> events = new ArrayList<>();
    }

    // ---------- Main ----------
    public static void main(String[] args) throws Exception {
        if (TOKEN == null || TOKEN.isBlank()) {
            System.err.println(">>> Error: set the CANVAS_TOKEN environment variable first.");
            System.exit(1);
        }
        String outputPath = args.length > 0 ? args[0] : "canvas_events.json";

        ZonedDateTime now = ZonedDateTime.now(ZONE);
        ZonedDateTime until = now.plusDays(DAYS_AHEAD);
        EventList result = new EventList();

        System.out.println(">>> Fetching courses from " + BASE_URL + " ...");
        JsonArray courses = getAllPages(BASE_URL + "/api/v1/courses?enrollment_state=active&per_page=100");

        for (JsonElement el : courses) {
            JsonObject course = el.getAsJsonObject();
            if (!course.has("id") || !course.has("name")) continue; // skips restricted courses
            long courseId = course.get("id").getAsLong();
            String courseName = str(course, "name");

            int before = result.events.size();
            addAssignments(result, courseId, courseName, now, until);
            addCalendarEvents(result, "course_" + courseId, courseName, now, until);
            System.out.println("    " + courseName + ": " + (result.events.size() - before) + " event(s)");
        }

        // Personal calendar events the user created themselves in Canvas
        addCalendarEvents(result, "user_self", null, now, until);

        result.events.sort(Comparator.comparing(e -> e.date));

        Files.writeString(Path.of(outputPath), GSON.toJson(result), StandardCharsets.UTF_8);
        System.out.println(">>> Saved " + result.events.size() + " event(s) to '" + outputPath + "'.");
    }

    // ---------- Assignments (due dates) ----------
    private static void addAssignments(EventList out, long courseId, String courseName,
                                       ZonedDateTime from, ZonedDateTime until) throws Exception {
        String url = BASE_URL + "/api/v1/courses/" + courseId + "/assignments?per_page=100&order_by=due_at";
        for (JsonElement el : getAllPages(url)) {
            JsonObject a = el.getAsJsonObject();
            String dueAt = str(a, "due_at");
            if (dueAt == null) continue; // no due date -> not an event

            ZonedDateTime due = Instant.parse(dueAt).atZone(ZONE);
            if (due.isBefore(from) || due.isAfter(until)) continue;

            out.events.add(new Event(
                    str(a, "name"),
                    due.format(DATE_TIME),
                    null,
                    buildDescription(courseName, str(a, "description"), str(a, "html_url"))));
        }
    }

    // ---------- Calendar events (exams, review sessions, office hours...) ----------
    private static void addCalendarEvents(EventList out, String contextCode, String courseName,
                                          ZonedDateTime from, ZonedDateTime until) throws Exception {
        String url = BASE_URL + "/api/v1/calendar_events?type=event&per_page=100"
                + "&context_codes%5B%5D=" + contextCode
                + "&start_date=" + from.toLocalDate()
                + "&end_date=" + until.toLocalDate();

        for (JsonElement el : getAllPages(url)) {
            JsonObject e = el.getAsJsonObject();
            String startAt = str(e, "start_at");
            if (startAt == null) continue;

            String date;
            if (e.has("all_day") && !e.get("all_day").isJsonNull() && e.get("all_day").getAsBoolean()) {
                String allDayDate = str(e, "all_day_date");
                date = allDayDate != null ? LocalDate.parse(allDayDate).format(DATE_ONLY)
                                          : Instant.parse(startAt).atZone(ZONE).format(DATE_ONLY);
            } else {
                date = Instant.parse(startAt).atZone(ZONE).format(DATE_TIME);
            }

            out.events.add(new Event(
                    str(e, "title"),
                    date,
                    joinLocation(str(e, "location_name"), str(e, "location_address")),
                    buildDescription(courseName, str(e, "description"), str(e, "html_url"))));
        }
    }

    // ---------- HTTP with Canvas pagination ----------
    private static JsonArray getAllPages(String url) throws IOException, InterruptedException {
        JsonArray all = new JsonArray();
        while (url != null) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 401) {
                throw new IOException("Canvas said 401 Unauthorized - check CANVAS_TOKEN.");
            }
            if (res.statusCode() == 403 || res.statusCode() == 404) {
                return all; // course hidden/closed: skip it instead of crashing
            }
            if (res.statusCode() != 200) {
                throw new IOException("Canvas returned " + res.statusCode() + " for " + url + "\n" + res.body());
            }

            JsonElement body = JsonParser.parseString(res.body());
            if (body.isJsonArray()) all.addAll(body.getAsJsonArray());

            url = res.headers().firstValue("Link").map(CanvasFetcher::nextLink).orElse(null);
        }
        return all;
    }

    private static String nextLink(String linkHeader) {
        Matcher m = NEXT_LINK.matcher(linkHeader);
        return m.find() ? m.group(1) : null;
    }

    // ---------- Small helpers ----------
    private static String buildDescription(String courseName, String html, String link) {
        StringBuilder sb = new StringBuilder();
        if (courseName != null) sb.append("Course: ").append(courseName).append(". ");
        String text = stripHtml(html);
        if (!text.isEmpty()) {
            if (text.length() > MAX_DESCRIPTION_CHARS) text = text.substring(0, MAX_DESCRIPTION_CHARS) + "...";
            sb.append(text).append(" ");
        }
        if (link != null) sb.append("Link: ").append(link);
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private static String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("(?s)<(script|style)[^>]*>.*?</\\1>", " ")
                   .replaceAll("<[^>]+>", " ")
                   .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                   .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                   .replaceAll("\\s+", " ")
                   .trim();
    }

    private static String joinLocation(String name, String address) {
        boolean hasName = name != null && !name.isBlank();
        boolean hasAddr = address != null && !address.isBlank();
        if (hasName && hasAddr) return name + ", " + address;
        if (hasName) return name;
        if (hasAddr) return address;
        return null;
    }

    private static String str(JsonObject o, String key) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : null;
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback.replaceAll("/+$", "") : v.replaceAll("/+$", "");
    }
}
