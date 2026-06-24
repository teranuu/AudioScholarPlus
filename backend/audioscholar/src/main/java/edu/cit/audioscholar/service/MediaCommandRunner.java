package edu.cit.audioscholar.service;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

public interface MediaCommandRunner {
	MediaProcessResult run(List<String> command, Duration timeout) throws IOException;
}
