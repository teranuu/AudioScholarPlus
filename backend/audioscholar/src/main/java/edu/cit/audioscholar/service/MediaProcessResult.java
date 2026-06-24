package edu.cit.audioscholar.service;

public record MediaProcessResult(int exitCode, String stdout, String stderr) {
}
