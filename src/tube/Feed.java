package tube;

import java.util.Hashtable;
import java.util.Vector;

/**
 * A list that can be continued: search results, popular, related, a
 * channel's videos or a video's comments. Page 1 can come from any server;
 * later pages come from the same one, since each server pages differently.
 */
final class Feed {
    static final int SEARCH = 1, POPULAR = 2, RELATED = 3, CHANNEL = 4, COMMENTS = 5;
    /** Kept on the phone (history, favourites): no server, no more pages. */
    static final int LOCAL = 6;

    final int what;
    /** The search words, video id (related, comments) or channel (UC... id or @handle). */
    final String arg;
    final String title;

    /** Server that answered page 1 (kind letter and base address). */
    String kind, base;
    int page;
    /** Invidious hands out a continuation token instead of page numbers. */
    String token;
    boolean end;

    /** Video, or String[] { author, text, extra } for comments. */
    final Vector items = new Vector();
    /** Some servers repeat earlier entries on later pages: those are dropped. */
    final Hashtable seen = new Hashtable();

    Feed(int what, String arg, String title) {
        this.what = what;
        this.arg = arg;
        this.title = title;
    }

    boolean comments() {
        return what == COMMENTS;
    }
}
