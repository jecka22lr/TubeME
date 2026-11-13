package tube;

/** One video, as much as the backend told us. */
final class Video {
    String id;
    String title = "";
    String author = "";
    String duration = "";
    String views = "";
    String published = "";
    String description = "";
    /** Thumbnail URL, may be empty. */
    String thumb = "";
    /** Ready-made H.264 stream URL when the backend hands one out (Invidious). */
    String stream;
    /** Server that answered for this video; tried first for playback. */
    String source;
    /** The uploader's channel: a UC... id or an @handle, "" when unknown. */
    String channel = "";

    /** One line for result lists. */
    String line() {
        StringBuffer b = new StringBuffer(title);
        if (duration.length() > 0) b.append(" [").append(duration).append(']');
        if (author.length() > 0) b.append(" - ").append(author);
        return b.toString();
    }
}
