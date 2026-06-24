package edu.cit.audioscholar.model;

import java.nio.file.Path;

public record AudioChunk(int index, long startMs, long endMs, boolean overlapsPrevious, Path path) {
}
