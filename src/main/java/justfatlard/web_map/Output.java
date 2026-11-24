package justfatlard.web_map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The folder the web server serves, and the one thread that writes into it. Every file is written
 * through a temporary file and moved into place, so the page never reads half of one, and the
 * folder is made again if someone deletes it while the server runs.
 */
final class Output {
	static final Logger LOGGER = LoggerFactory.getLogger(Main.MOD_ID);

	final Path root;
	private final ExecutorService writer;

	Output(Path root) {
		this.root = root;
		this.writer = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "web-map writer");
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
	}

	/** Runs on the writer thread; an exception is logged, never thrown into the caller. */
	void later(String what, IoTask task) {
		Runnable run = () -> {
			try {
				task.run();
			} catch (Exception e) {
				LOGGER.warn("Could not {}", what, e);
			}
		};
		try {
			writer.execute(run);
		} catch (RejectedExecutionException closing) {
			// Queued while the server stops, by a write still finishing: done here rather than dropped
			run.run();
		}
	}

	void writeLater(String name, String content) {
		later("write " + name, () -> write(name, content));
	}

	/** On the calling thread: for the few writes that must land before the server stops. */
	void write(String name, String content) throws IOException {
		Path target = root.resolve(name);
		Files.createDirectories(target.getParent());
		Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
		Files.writeString(temporary, content, StandardCharsets.UTF_8);
		Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	void copyResource(String resource, String name) throws IOException {
		try (InputStream in = Main.class.getClassLoader().getResourceAsStream(resource)) {
			if (in == null) throw new IOException("missing " + resource);
			Path target = root.resolve(name);
			Files.createDirectories(target.getParent());
			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** Waits up to the given time for queued writes, then stops the thread. Never throws. */
	void close(long seconds) {
		try {
			writer.shutdown();
			if (!writer.awaitTermination(seconds, TimeUnit.SECONDS)) {
				LOGGER.warn("Stopped before every tile was written; they are drawn again as their chunks load");
			}
		} catch (Exception e) {
			LOGGER.warn("Could not finish writing", e);
		}
	}

	interface IoTask {
		void run() throws Exception;
	}
}
