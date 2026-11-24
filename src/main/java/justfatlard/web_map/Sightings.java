package justfatlard.web_map;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * What was seen when, kept just long enough to show it a delay late: the newest sighting at least
 * the delay old is the one shown, and nothing until there is one. Server thread only.
 */
final class Sightings<T> {
	private record Seen<T>(long time, T what) {}

	private final ArrayDeque<Seen<T>> seen = new ArrayDeque<>();

	/** Records what is seen now, and answers what to show: what was seen the delay ago, or null. */
	T see(long now, T what, long delayMillis) {
		seen.addLast(new Seen<>(now, what));
		long shownFrom = now - Math.max(0, delayMillis);
		while (seen.size() > 1) {
			Iterator<Seen<T>> oldest = seen.iterator();
			oldest.next();
			if (oldest.next().time() > shownFrom) break;
			seen.removeFirst();
		}
		Seen<T> first = seen.peekFirst();
		return first.time() <= shownFrom ? first.what() : null;
	}
}
